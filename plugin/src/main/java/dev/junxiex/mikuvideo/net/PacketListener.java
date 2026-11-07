package dev.junxiex.mikuvideo.net;

import dev.junxiex.mikuvideo.config.Settings;
import dev.junxiex.mikuvideo.credential.CookieService;
import dev.junxiex.mikuvideo.menu.MenuService;
import dev.junxiex.mikuvideo.permission.Permissions;
import dev.junxiex.mikuvideo.playback.PlaybackService;
import dev.junxiex.mikuvideo.protocol.Message;
import dev.junxiex.mikuvideo.protocol.MessageCodec;
import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.screen.Screen;
import dev.junxiex.mikuvideo.screen.ScreenManager;
import dev.junxiex.mikuvideo.util.Tasks;
import dev.junxiex.mikuvideo.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 客户端消息入口。
 *
 * <p>回调跑在 Netty 线程上，因此这里只做解码、鉴权与转发；
 * 涉及世界或玩家状态的操作用 {@link Tasks#runFor} 投递到玩家区域线程。</p>
 *
 * <p>媒体错误与日志都做了限流，避免一个坏链接把控制台刷满。</p>
 *
 * @author JunXieX
 */
public final class PacketListener implements PluginMessageListener {

    /** 同一玩家同一屏幕的媒体错误提示间隔。 */
    private static final long MUTE_MILLIS = 60_000L;
    private static final int MUTE_LIMIT = 2048;

    private final Plugin plugin;
    private final ClientRegistry clients;
    private final SyncService sync;
    private final ScreenManager screens;
    private final PlaybackService playback;
    private final MenuService menu;
    private final CookieService cookieService;
    private final Supplier<Settings> settings;
    private final Tasks tasks;
    /** 解码失败日志的最小间隔，避免恶意客户端刷控制台。 */
    private static final long WARN_INTERVAL_MILLIS = 5000L;
    /** 同一玩家重复握手的忽略窗口。 */
    private static final long HELLO_COOLDOWN_MILLIS = 1000L;
    /** 同一玩家请求全量同步的最小间隔。 */
    private static final long SYNC_REQUEST_COOLDOWN_MILLIS = 1000L;

    private final Map<String, Long> muted = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWarnAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHelloAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSyncAt = new ConcurrentHashMap<>();

    public PacketListener(Plugin plugin, ClientRegistry clients, SyncService sync, ScreenManager screens,
                          PlaybackService playback, MenuService menu, CookieService cookieService,
                          Supplier<Settings> settings, Tasks tasks) {
        this.plugin = plugin;
        this.clients = clients;
        this.sync = sync;
        this.screens = screens;
        this.playback = playback;
        this.menu = menu;
        this.cookieService = cookieService;
        this.settings = settings == null ? Settings::defaults : settings;
        this.tasks = tasks;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!Protocol.CHANNEL.equals(channel) || player == null || data == null) {
            return;
        }
        Message message;
        try {
            message = MessageCodec.decode(data);
        } catch (RuntimeException error) {
            warnDecodeFailure(player, error);
            return;
        }
        switch (message) {
            case Message.HelloC2S hello -> handleHello(player, hello);
            case Message.RequestSyncC2S ignored -> handleRequestSync(player);
            case Message.CredentialRequestC2S ignored -> handleCredentialRequest(player);
            case Message.InteractC2S interact -> handleInteract(player, interact);
            case Message.MediaErrorC2S error -> handleMediaError(player, error);
            case Message.PongC2S pong -> handlePong(player, pong);
            case Message.DurationC2S duration -> handleDuration(player, duration);
            default -> {
                // 服务端方向的 op 出现在这里说明客户端实现有误，忽略并记录
                plugin.getLogger().fine("忽略客户端发来的服务端消息 op=" + message.op());
            }
        }
    }

    private void warnDecodeFailure(Player player, RuntimeException error) {
        long now = System.currentTimeMillis();
        Long last = lastWarnAt.put(player.getUniqueId(), now);
        if (last != null && now - last < WARN_INTERVAL_MILLIS) {
            return;
        }
        if (lastWarnAt.size() > MUTE_LIMIT) {
            lastWarnAt.entrySet().removeIf(entry -> now - entry.getValue() > WARN_INTERVAL_MILLIS);
        }
        plugin.getLogger().warning("收到无法解析的客户端消息（" + player.getName() + "）: " + error.getMessage());
    }

    private void handleHello(Player player, Message.HelloC2S hello) {
        long now = System.currentTimeMillis();
        Long previous = lastHelloAt.put(player.getUniqueId(), now);
        if (previous != null && now - previous < HELLO_COOLDOWN_MILLIS) {
            // 重复握手直接忽略，防止客户端刷全量同步
            return;
        }
        ClientRegistry.Handshake result = clients.handshake(player, hello);
        if (!result.ok()) {
            sync.notice(player, Protocol.NOTICE_ERROR, "<red>" + result.error() + "，请更新客户端模组");
            return;
        }
        sync.sendHello(player);
        tasks.runFor(player, () -> {
            if (player.isOnline()) {
                sync.syncNow(player);
            }
        });
        if (cookieService != null) {
            cookieService.scheduleDelivery(player);
        }
    }

    private void handleCredentialRequest(Player player) {
        if (!clients.isRegistered(player.getUniqueId()) || cookieService == null) {
            return;
        }
        cookieService.request(player);
    }

    private void handleRequestSync(Player player) {
        if (!clients.isRegistered(player.getUniqueId())) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastSyncAt.put(player.getUniqueId(), now);
        if (previous != null && now - previous < SYNC_REQUEST_COOLDOWN_MILLIS) {
            return;
        }
        if (lastSyncAt.size() > MUTE_LIMIT) {
            lastSyncAt.entrySet().removeIf(entry -> now - entry.getValue() > SYNC_REQUEST_COOLDOWN_MILLIS);
        }
        sync.requestFullSync(player);
    }

    private void handleInteract(Player player, Message.InteractC2S interact) {
        if (!clients.isRegistered(player.getUniqueId())) {
            return;
        }
        if (interact.action() != Protocol.INTERACT_OPEN_MENU) {
            return;
        }
        Screen screen = screens.get(interact.screenId()).orElse(null);
        if (screen == null) {
            return;
        }
        tasks.runFor(player, () -> {
            Screen current = screens.get(interact.screenId()).orElse(null);
            if (current == null || !player.isOnline()) {
                return;
            }
            boolean owner = current.ownedBy(player.getUniqueId());
            boolean crossManage = player.hasPermission(Permissions.MANAGE_OTHERS);
            boolean visible = current.publicScreen() || owner || crossManage;
            if (!visible) {
                return;
            }
            boolean canManage = owner ? player.hasPermission(Permissions.MANAGE_OWN) : crossManage;
            if (!canManage) {
                sync.notice(player, Protocol.NOTICE_WARN, settings.get().messages().noPermission());
                return;
            }
            menu.openControl(player, current.id());
        });
    }

    private void handleMediaError(Player player, Message.MediaErrorC2S error) {
        long now = System.currentTimeMillis();
        String key = player.getUniqueId() + "|" + error.screenId();
        Long until = muted.get(key);
        if (until != null && until > now) {
            return;
        }
        if (muted.size() > MUTE_LIMIT) {
            muted.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        muted.put(key, now + MUTE_MILLIS);
        String detail = Text.safe(error.message(), Protocol.MAX_ERROR_MESSAGE);
        plugin.getLogger().warning("客户端媒体错误：" + player.getName() + " 屏幕 " + error.screenId()
                + " code=" + error.code() + " message=" + detail);
        Screen screen = screens.get(error.screenId()).orElse(null);
        if (screen == null) {
            return;
        }
        if (!screen.publicScreen() && !screen.ownedBy(player.getUniqueId())
                && !player.hasPermission(Permissions.MANAGE_OTHERS)) {
            return;
        }
        Player ownerPlayer = Bukkit.getPlayer(screen.owner());
        if (ownerPlayer != null && !ownerPlayer.getUniqueId().equals(player.getUniqueId())) {
            sync.notice(ownerPlayer, Protocol.NOTICE_WARN, "<yellow>你的屏幕 " + screen.name()
                    + " 在客户端播放失败：" + Text.trimBytes(detail, 128));
        }
    }

    private void handlePong(Player player, Message.PongC2S pong) {
        long now = System.currentTimeMillis();
        if (pong.id() < 0L) {
            return;
        }
        clients.pong(player.getUniqueId(), now);
        sync.recordPong(player.getUniqueId(), now);
    }

    private void handleDuration(Player player, Message.DurationC2S duration) {
        if (duration.durationMillis() <= 0L) {
            return;
        }
        if (!clients.isRegistered(player.getUniqueId())) {
            return;
        }
        tasks.runFor(player, () -> {
            Screen screen = screens.get(duration.screenId()).orElse(null);
            if (screen == null || !player.isOnline()) {
                return;
            }
            if (!screen.world().equals(player.getWorld().getKey().toString())) {
                return;
            }
            if (screen.distanceSquared(player.getEyeLocation()) > settings.get().sync().viewDistanceSquared()) {
                return;
            }
            if (!duration.url().equals(playback.state(screen.id()).url())) {
                // 上报的是上一部片子的时长，切源之后到达，丢弃
                return;
            }
            boolean authoritative = screen.ownedBy(player.getUniqueId())
                    || player.hasPermission(Permissions.MANAGE_OTHERS);
            playback.reportDuration(screen, duration.durationMillis(), authoritative);
        });
    }
}