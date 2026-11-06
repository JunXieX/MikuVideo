package dev.junxiex.mikuvideo.listener;

import dev.junxiex.mikuvideo.net.ClientRegistry;
import dev.junxiex.mikuvideo.net.SyncService;
import dev.junxiex.mikuvideo.util.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 玩家生命周期钩子。
 *
 * <p>进服后等 1 秒再补一次同步，覆盖客户端 HELLO 比出生点加载更晚到达的情况；
 * 换世界与重生都会重建可见集合，避免旧世界的屏幕残留在客户端。</p>
 *
 * @author JunXieX
 */
public final class PlayerListener implements Listener {

    /** 进服补同步延迟（tick）。 */
    private static final long JOIN_SYNC_DELAY_TICKS = 20L;

    private final SyncService sync;
    private final ClientRegistry clients;
    private final Tasks tasks;

    public PlayerListener(SyncService sync, ClientRegistry clients, Tasks tasks) {
        this.sync = sync;
        this.clients = clients;
        this.tasks = tasks;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        tasks.globalLater(() -> tasks.runFor(player, () -> {
            if (player.isOnline() && clients.isRegistered(player.getUniqueId())) {
                sync.syncNow(player);
            }
        }), JOIN_SYNC_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        clients.remove(player.getUniqueId());
        sync.forget(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        tasks.runFor(player, () -> sync.handleWorldChange(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        tasks.globalLater(() -> tasks.runFor(player, () -> {
            if (player.isOnline()) {
                sync.handleWorldChange(player);
            }
        }), 1L);
    }
}
