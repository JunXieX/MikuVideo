package dev.junxiex.mikuvideo.credential;

import dev.junxiex.mikuvideo.config.Settings;
import dev.junxiex.mikuvideo.net.SyncService;
import dev.junxiex.mikuvideo.protocol.Message;
import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.util.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * B 站凭证下发。
 *
 * <p>只要配置了 Cookie 并且开启下发，玩家进服后就会收到；不再做权限、正版验证
 * 或频率拦截。客户端负责加密保存并绑定服务器 ID，命令与日志都不打印 Cookie 内容。</p>
 *
 * @author JunXieX
 */
public final class CookieService {
    private final Plugin plugin;
    private final SyncService sync;
    private final Tasks tasks;
    private final Supplier<Settings> settings;
    private final String serverId;
    private final AtomicLong delivered = new AtomicLong();

    public CookieService(Plugin plugin, SyncService sync, Tasks tasks, Supplier<Settings> settings, String serverId) {
        this.plugin = plugin;
        this.sync = sync;
        this.tasks = tasks;
        this.settings = settings == null ? Settings::defaults : settings;
        this.serverId = serverId == null ? "" : serverId;
    }

    /** 玩家完成握手后调用；未配置或关闭下发时静默跳过。 */
    public void scheduleDelivery(Player player) {
        if (player == null) {
            return;
        }
        Settings.Bilibili bilibili = settings.get().bilibili();
        if (!bilibili.configured() || !bilibili.deliverToClients()) {
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
        tasks.runFor(player, () -> deliver(player, true));
    }

    /** 管理命令用的状态文本，永远不包含 Cookie 内容。 */
    public String statusText() {
        Settings.Bilibili bilibili = settings.get().bilibili();
        return "凭证：" + (bilibili.configured() ? "已配置" : "未配置")
                + "，下发：" + (bilibili.deliverToClients() ? "开" : "关")
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
        if (!bilibili.configured() || !bilibili.deliverToClients()) {
            if (requested) {
                notice(player, "服务器未配置 B 站凭证");
            }
            return;
        }
        long now = System.currentTimeMillis();
        long expiresAt = now + bilibili.credentialTtlMillis();
        sync.sendCredential(player, new Message.CredentialS2C(serverId, bilibili.cookie(), now, expiresAt));
        delivered.incrementAndGet();
        plugin.getLogger().info("已向 " + player.getName() + " 下发 B 站凭证（" + bilibili.ttlHours() + " 小时有效）");
    }

    private void notice(Player player, String message) {
        if (player != null && player.isOnline()) {
            sync.notice(player, Protocol.NOTICE_WARN, message);
        }
    }
}
