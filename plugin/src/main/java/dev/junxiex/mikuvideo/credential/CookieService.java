package dev.junxiex.mikuvideo.credential;

import dev.junxiex.mikuvideo.config.Settings;
import dev.junxiex.mikuvideo.net.SyncService;
import dev.junxiex.mikuvideo.permission.Permissions;
import dev.junxiex.mikuvideo.protocol.Message;
import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * B 站凭证下发。
 *
 * <p>Cookie 只存在于服务端配置里，按「权限 + 正版验证 + 频率限制」三条件下发，
 * 每次下发都记录日志但不记录 Cookie 内容。客户端拿到后自行加密并绑定服务器 ID。</p>
 *
 * @author JunXieX
 */
public final class CookieService {
    private static final long HOUR_MILLIS = 3_600_000L;

    private final Plugin plugin;
    private final SyncService sync;
    private final Tasks tasks;
    private final Supplier<Settings> settings;
    private final String serverId;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong delivered = new AtomicLong();

    public CookieService(Plugin plugin, SyncService sync, Tasks tasks, Supplier<Settings> settings, String serverId) {
        this.plugin = plugin;
        this.sync = sync;
        this.tasks = tasks;
        this.settings = settings == null ? Settings::defaults : settings;
        this.serverId = serverId == null ? "" : serverId;
    }

    /** 玩家完成握手后调用；不满足条件时静默跳过，避免进服刷屏。 */
    public void scheduleDelivery(Player player) {
        if (player == null) {
            return;
        }
        Settings.Bilibili bilibili = settings.get().bilibili();
        if (!eligible(player, bilibili)) {
            return;
        }
        long delayTicks = Math.max(1L, bilibili.deliverDelayMs() / 50L);
        tasks.globalLater(() -> tasks.runFor(player, () -> deliver(player, false)), delayTicks);
    }

    /** 客户端主动申请（换 Cookie、过期重取）。 */
    public void request(Player player) {
        if (player == null) {
            return;
        }
        Settings.Bilibili bilibili = settings.get().bilibili();
        if (!bilibili.configured() || !bilibili.deliverToClients()) {
            notice(player, "服务器未配置 B 站凭证");
            return;
        }
        if (!player.hasPermission(Permissions.BILIBILI_COOKIE)) {
            notice(player, "你没有权限获取服务器 B 站凭证");
            return;
        }
        if (onlineModeBlocked(bilibili)) {
            notice(player, "服务器未开启正版验证，已拒绝下发凭证");
            return;
        }
        Window window = window(player.getUniqueId());
        long now = System.currentTimeMillis();
        synchronized (window) {
            if (now - window.lastDelivered < bilibili.refreshCooldownMs()) {
                notice(player, "请求过于频繁，请稍后再试");
                return;
            }
            if (window.windowStart + HOUR_MILLIS > now && window.count >= bilibili.maxDeliveriesPerHour()) {
                notice(player, "本小时获取次数已达上限");
                return;
            }
        }
        tasks.runFor(player, () -> deliver(player, true));
    }

    /** 管理命令用的状态文本，永远不包含 Cookie 内容。 */
    public String statusText() {
        Settings.Bilibili bilibili = settings.get().bilibili();
        return "凭证：" + (bilibili.configured() ? "已配置" : "未配置")
                + "，下发：" + (bilibili.deliverToClients() ? "开" : "关")
                + "，要求正版验证：" + (bilibili.requireOnlineMode() ? "是" : "否")
                + "，有效期：" + bilibili.ttlHours() + " 小时"
                + "，累计下发：" + delivered.get() + " 次";
    }

    public String serverId() {
        return serverId;
    }

    private void deliver(Player player, boolean requested) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Settings.Bilibili bilibili = settings.get().bilibili();
        if (!eligible(player, bilibili)) {
            if (requested) {
                notice(player, "当前条件不允许下发凭证");
            }
            return;
        }
        Window window = window(player.getUniqueId());
        long now = System.currentTimeMillis();
        synchronized (window) {
            if (window.windowStart + HOUR_MILLIS <= now) {
                window.windowStart = now;
                window.count = 0;
            }
            if (window.count >= bilibili.maxDeliveriesPerHour()) {
                if (requested) {
                    notice(player, "本小时获取次数已达上限");
                }
                return;
            }
            window.count++;
            window.lastDelivered = now;
        }
        long expiresAt = now + bilibili.credentialTtlMillis();
        sync.sendCredential(player, new Message.CredentialS2C(serverId, bilibili.cookie(), now, expiresAt));
        delivered.incrementAndGet();
        plugin.getLogger().info("已向 " + player.getName() + " 下发 B 站凭证（" + bilibili.ttlHours() + " 小时有效）");
    }

    private boolean eligible(Player player, Settings.Bilibili bilibili) {
        if (bilibili == null || !bilibili.configured() || !bilibili.deliverToClients()) {
            return false;
        }
        if (!player.hasPermission(Permissions.BILIBILI_COOKIE)) {
            return false;
        }
        return !onlineModeBlocked(bilibili);
    }

    private boolean onlineModeBlocked(Settings.Bilibili bilibili) {
        if (!bilibili.requireOnlineMode()) {
            return false;
        }
        return !Bukkit.getOnlineMode() && !bilibili.allowOfflineMode();
    }

    private Window window(UUID playerId) {
        if (windows.size() > 4096) {
            long cutoff = System.currentTimeMillis() - 2L * HOUR_MILLIS;
            windows.entrySet().removeIf(entry -> entry.getValue().lastDelivered < cutoff);
        }
        return windows.computeIfAbsent(playerId, key -> new Window());
    }

    private void notice(Player player, String message) {
        if (player != null && player.isOnline()) {
            sync.notice(player, Protocol.NOTICE_WARN, message);
        }
    }

    private static final class Window {
        private long windowStart = System.currentTimeMillis();
        private long lastDelivered;
        private int count;
    }
}
