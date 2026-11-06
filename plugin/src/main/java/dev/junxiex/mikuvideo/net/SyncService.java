package dev.junxiex.mikuvideo.net;

import dev.junxiex.mikuvideo.config.Settings;
import dev.junxiex.mikuvideo.permission.Permissions;
import dev.junxiex.mikuvideo.playback.PlaybackService;
import dev.junxiex.mikuvideo.protocol.Message;
import dev.junxiex.mikuvideo.protocol.MessageCodec;
import dev.junxiex.mikuvideo.protocol.Playback;
import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.protocol.ScreenInfo;
import dev.junxiex.mikuvideo.screen.Screen;
import dev.junxiex.mikuvideo.screen.ScreenManager;
import dev.junxiex.mikuvideo.util.Tasks;
import dev.junxiex.mikuvideo.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 屏幕同步服务。
 *
 * <p>维护「玩家 → 可见屏幕」与「屏幕 → 可见玩家」双向索引：
 * 前者用于做增量 diff，后者用于屏幕变更时精准推送，避免每次变更都全服遍历。</p>
 *
 * <p>所有发送都投递到目标玩家自己的区域线程，协议编码在调用线程完成（纯 CPU），
 * 一次同步的多条消息合并成一次投递，避免 Folia 上出现调度风暴。</p>
 *
 * @author JunXieX
 */
public final class SyncService implements PlaybackService.Broadcaster, ScreenManager.Listener {

    private final Plugin plugin;
    private final ScreenManager screens;
    private final PlaybackService playback;
    private final ClientRegistry clients;
    private final Supplier<Settings> settings;
    private final Tasks tasks;
    private final String serverId;
    private final Map<UUID, Set<String>> visible = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, String> worlds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pingSentAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> rttMillis = new ConcurrentHashMap<>();
    private final AtomicLong pingSequence = new AtomicLong();
    private final AtomicLong sendFailures = new AtomicLong();

    public SyncService(Plugin plugin, ScreenManager screens, PlaybackService playback, ClientRegistry clients,
                       String serverId, Supplier<Settings> settings, Tasks tasks) {
        this.plugin = plugin;
        this.screens = screens;
        this.playback = playback;
        this.clients = clients;
        this.settings = settings == null ? Settings::defaults : settings;
        this.tasks = tasks;
        this.serverId = serverId == null ? "" : serverId;
    }

    // ---------------------------------------------------------------- 握手与全量同步

    /** 回复 HELLO。 */
    public void sendHello(Player player) {
        if (player == null) {
            return;
        }
        Settings current = settings.get();
        String serverName = plugin.getPluginMeta().getName() + " " + plugin.getPluginMeta().getVersion();
        send(player, new Message.HelloS2C(Protocol.VERSION, System.currentTimeMillis(), 0,
                Text.trimBytes(serverName, 128), current.messages().clientDownloadUrl(), serverId));
    }

    /** 全量同步：Clear 之外的一次完整下发（世界内可见屏幕 + 每屏播放状态）。 */
    public void syncNow(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!clients.isRegistered(player.getUniqueId())) {
            return;
        }
        String world = player.getWorld().getName();
        worlds.put(player.getUniqueId(), world);
        List<Screen> found = visibleScreens(player);
        if (found.size() > Protocol.MAX_SCREEN_COUNT) {
            plugin.getLogger().warning("世界 " + world + " 中可见屏幕 " + found.size()
                    + " 块，超过协议上限 " + Protocol.MAX_SCREEN_COUNT + "，本次只同步前 "
                    + Protocol.MAX_SCREEN_COUNT + " 块");
            found = found.subList(0, Protocol.MAX_SCREEN_COUNT);
        }
        List<ScreenInfo> infos = new ArrayList<>(found.size());
        List<Message> messages = new ArrayList<>(found.size() + 1);
        for (Screen screen : found) {
            infos.add(screen.toInfo());
        }
        messages.add(new Message.ScreenSyncS2C(infos));
        for (Screen screen : found) {
            messages.add(new Message.PlaybackS2C(screen.id(), playback.stateOf(screen)));
        }
        applyVisible(player, found);
        sendAll(player, messages);
    }

    /** 请求重发全量（客户端 REQUEST_SYNC 或重连）。 */
    public void requestFullSync(Player player) {
        if (player == null) {
            return;
        }
        tasks.runFor(player, () -> syncNow(player));
    }

    // ---------------------------------------------------------------- 增量同步

    /** 单个玩家的可见集合 diff。 */
    public void rescan(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!clients.isRegistered(player.getUniqueId())) {
            return;
        }
        String world = player.getWorld().getName();
        String last = worlds.put(player.getUniqueId(), world);
        if (last != null && !last.equals(world)) {
            clearAndSync(player);
            return;
        }
        List<Screen> current = visibleScreens(player);
        Set<String> previous = visible.getOrDefault(player.getUniqueId(), Set.of());
        Set<String> next = ScreenManager.ids(current);
        List<Message> messages = new ArrayList<>();
        for (Screen screen : current) {
            if (!previous.contains(screen.id())) {
                messages.add(new Message.ScreenUpsertS2C(screen.toInfo()));
                messages.add(new Message.PlaybackS2C(screen.id(), playback.stateOf(screen)));
            }
        }
        for (String id : previous) {
            if (!next.contains(id)) {
                messages.add(new Message.ScreenRemoveS2C(id));
            }
        }
        applyVisible(player, current);
        if (!messages.isEmpty()) {
            sendAll(player, messages);
        }
    }

    /** 全服扫描，由周期任务调用；逐个玩家投递到各自区域线程。 */
    public void rescanAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!clients.isRegistered(player.getUniqueId())) {
                continue;
            }
            tasks.runFor(player, () -> {
                if (player.isOnline()) {
                    rescan(player);
                }
            });
        }
    }

    /** 换世界：先 Clear 再发新世界的全量。 */
    public void handleWorldChange(Player player) {
        if (player == null || !player.isOnline() || !clients.isRegistered(player.getUniqueId())) {
            return;
        }
        String world = player.getWorld().getName();
        String last = worlds.get(player.getUniqueId());
        worlds.put(player.getUniqueId(), world);
        if (last != null && last.equals(world)) {
            rescan(player);
            return;
        }
        clearAndSync(player);
    }

    // ---------------------------------------------------------------- 心跳

    /** 周期心跳：只标记超时，不踢人。 */
    public void pingTick() {
        long now = System.currentTimeMillis();
        for (ClientRegistry.Client client : clients.snapshot()) {
            Player player = Bukkit.getPlayer(client.playerId());
            if (player == null) {
                continue;
            }
            long idle = now - client.lastSeen();
            if (idle > ClientRegistry.PONG_TIMEOUT_MILLIS && !client.overdue()) {
                clients.markOverdue(client.playerId());
                plugin.getLogger().warning("客户端 " + client.name() + " 已 " + (idle / 1000L)
                        + " 秒未回应心跳，已标记（不会踢出）");
            }
            pingSentAt.put(client.playerId(), now);
            send(player, new Message.PingS2C(pingSequence.incrementAndGet()));
        }
    }

    /** 记录一次心跳往返耗时。 */
    public void recordPong(UUID playerId, long now) {
        if (playerId == null) {
            return;
        }
        Long sent = pingSentAt.remove(playerId);
        if (sent != null) {
            rttMillis.put(playerId, Math.max(0L, now - sent));
        }
    }

    /** 平均往返耗时，没有样本时返回 -1。 */
    public long averageRttMillis() {
        long sum = 0L;
        int count = 0;
        for (long value : rttMillis.values()) {
            sum += value;
            count++;
        }
        return count == 0 ? -1L : sum / count;
    }

    // ---------------------------------------------------------------- 广播实现

    @Override
    public void playbackChanged(Screen screen, Playback state) {
        if (screen == null || state == null) {
            return;
        }
        Set<UUID> audience = viewers.get(screen.id());
        if (audience == null || audience.isEmpty()) {
            return;
        }
        List<byte[]> payloads = encode(List.of(new Message.PlaybackS2C(screen.id(), state)));
        if (payloads.isEmpty()) {
            return;
        }
        for (UUID id : List.copyOf(audience)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && stillVisible(screen, player)) {
                deliver(player, payloads);
            }
        }
    }

    @Override
    public void screenChanged(Screen screen) {
        if (screen == null) {
            return;
        }
        Set<UUID> audience = viewers.get(screen.id());
        if (audience != null && !audience.isEmpty()) {
            List<byte[]> payloads = encode(List.of(new Message.ScreenUpsertS2C(screen.toInfo())));
            for (UUID id : List.copyOf(audience)) {
                Player player = Bukkit.getPlayer(id);
                // 从公开改为私有时不再下发给无权观看的玩家，稍后的 rescan 会给他们补 Remove
                if (player != null && stillVisible(screen, player)) {
                    deliver(player, payloads);
                }
            }
        }
        // 公开性 / 尺寸变化后，同世界里还看不到它的玩家需要补一次 diff
        rescanWorld(screen.world(), screen.id());
    }

    @Override
    public boolean hasNearbyViewer(Screen screen) {
        if (screen == null) {
            return false;
        }
        Set<UUID> audience = viewers.get(screen.id());
        return audience != null && !audience.isEmpty();
    }

    @Override
    public void onScreenCreated(Screen screen) {
        if (screen != null) {
            rescanWorld(screen.world(), screen.id());
        }
    }

    @Override
    public void onScreenUpdated(Screen screen) {
        screenChanged(screen);
    }

    @Override
    public void onScreenRemoved(Screen screen) {
        if (screen == null) {
            return;
        }
        Set<UUID> audience = viewers.remove(screen.id());
        if (audience == null || audience.isEmpty()) {
            return;
        }
        List<byte[]> payloads = encode(List.of(new Message.ScreenRemoveS2C(screen.id())));
        for (UUID id : List.copyOf(audience)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                deliver(player, payloads);
            }
            forget(id, screen.id());
        }
    }

    // ---------------------------------------------------------------- 玩家生命周期

    /** 玩家退出 / 下线清理。 */
    public void forget(Player player) {
        if (player == null) {
            return;
        }
        forget(player.getUniqueId(), null);
        worlds.remove(player.getUniqueId());
        pingSentAt.remove(player.getUniqueId());
        rttMillis.remove(player.getUniqueId());
    }

    /** 全量清理，关服使用。 */
    public void clear() {
        visible.clear();
        viewers.clear();
        worlds.clear();
        pingSentAt.clear();
        rttMillis.clear();
    }

    /** 下发凭证；单独暴露，便于下发层做频率限制与审计。 */
    public void sendCredential(Player player, Message.CredentialS2C credential) {
        send(player, credential);
    }

    /** 发送一条提示，用于命令、交互与媒体错误通知。 */
    public void notice(Player player, int level, String message) {
        if (player == null) {
            return;
        }
        send(player, new Message.NoticeS2C(level, Text.trimBytes(message, Protocol.MAX_NOTICE)));
    }

    /** 已记录发送失败次数，用于 stats。 */
    public long sendFailures() {
        return sendFailures.get();
    }

    /** 当前已同步的玩家数量。 */
    public int trackedPlayers() {
        return visible.size();
    }

    // ---------------------------------------------------------------- 内部实现

    private boolean stillVisible(Screen screen, Player player) {
        return screens.visible(screen, player.getUniqueId(), player.hasPermission(Permissions.MANAGE_OTHERS));
    }

    private void clearAndSync(Player player) {
        applyVisible(player, List.of());
        send(player, new Message.ClearS2C());
        syncNow(player);
    }

    private List<Screen> visibleScreens(Player player) {
        Settings current = settings.get();
        double limit = current.sync().viewDistanceSquared();
        String world = player.getWorld().getName();
        UUID id = player.getUniqueId();
        boolean all = player.hasPermission(Permissions.MANAGE_OTHERS);
        // 眼睛坐标只取一次，避免在屏幕循环里反复构造 Location
        Vector eye = player.getEyeLocation().toVector();
        List<Screen> result = new ArrayList<>();
        for (Screen screen : screens.inWorld(world)) {
            if (!screens.visible(screen, id, all)) {
                continue;
            }
            if (screen.distanceSquared(eye.getX(), eye.getY(), eye.getZ()) > limit) {
                continue;
            }
            result.add(screen);
        }
        return result;
    }

    /** 用新集合覆盖双向索引。 */
    private void applyVisible(Player player, Collection<Screen> current) {
        UUID playerId = player.getUniqueId();
        Set<String> next = new HashSet<>(Math.max(4, current.size()));
        for (Screen screen : current) {
            next.add(screen.id());
        }
        Set<String> immutable = Set.copyOf(next);
        Set<String> previous = visible.put(playerId, immutable);
        if (previous != null) {
            for (String id : previous) {
                if (!next.contains(id)) {
                    removeViewer(id, playerId);
                }
            }
        }
        for (String id : next) {
            viewers.computeIfAbsent(id, key -> ConcurrentHashMap.newKeySet()).add(playerId);
        }
    }

    private void rescanWorld(String world, String excludeScreenId) {
        if (world == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!clients.isRegistered(player.getUniqueId())) {
                continue;
            }
            if (!player.getWorld().getName().equals(world)) {
                continue;
            }
            Set<String> known = visible.get(player.getUniqueId());
            if (known != null && known.contains(excludeScreenId)) {
                continue;
            }
            tasks.runFor(player, () -> {
                if (player.isOnline()) {
                    rescan(player);
                }
            });
        }
    }

    private void forget(UUID playerId, String screenId) {
        if (playerId == null) {
            return;
        }
        Set<String> previous = visible.get(playerId);
        if (screenId == null) {
            visible.remove(playerId);
        } else if (previous != null) {
            Set<String> next = new HashSet<>(previous);
            next.remove(screenId);
            visible.put(playerId, Set.copyOf(next));
        }
        if (previous != null) {
            for (String id : previous) {
                if (screenId == null || screenId.equals(id)) {
                    removeViewer(id, playerId);
                }
            }
        }
    }

    private void removeViewer(String screenId, UUID playerId) {
        Set<UUID> set = viewers.get(screenId);
        if (set != null) {
            set.remove(playerId);
            if (set.isEmpty()) {
                viewers.remove(screenId, set);
            }
        }
    }

    private void send(Player player, Message message) {
        sendAll(player, List.of(message));
    }

    private void sendAll(Player player, List<Message> messages) {
        if (player == null || messages.isEmpty()) {
            return;
        }
        List<byte[]> payloads = encode(messages);
        if (!payloads.isEmpty()) {
            deliver(player, payloads);
        }
    }

    private List<byte[]> encode(List<Message> messages) {
        List<byte[]> payloads = new ArrayList<>(messages.size());
        for (Message message : messages) {
            try {
                payloads.add(MessageCodec.encode(message));
            } catch (RuntimeException error) {
                sendFailures.incrementAndGet();
                plugin.getLogger().warning("编码协议消息失败: " + error.getMessage());
            }
        }
        return payloads;
    }

    private void deliver(Player player, List<byte[]> payloads) {
        if (player == null || payloads.isEmpty()) {
            return;
        }
        tasks.runFor(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            for (byte[] payload : payloads) {
                try {
                    player.sendPluginMessage(plugin, Protocol.CHANNEL, payload);
                } catch (RuntimeException error) {
                    sendFailures.incrementAndGet();
                    plugin.getLogger().warning("向 " + player.getName() + " 发送协议消息失败: " + error.getMessage());
                    return;
                }
            }
        });
    }
}