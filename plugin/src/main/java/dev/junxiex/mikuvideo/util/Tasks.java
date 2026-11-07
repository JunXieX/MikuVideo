package dev.junxiex.mikuvideo.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 调度封装。
 *
 * <p>全部走 Paper 的区域化调度器：全局逻辑用 {@code GlobalRegionScheduler}，
 * 玩家相关操作用 {@code EntityScheduler}，异步写盘用 {@code AsyncScheduler}。
 * 任何情况下都不使用 {@code BukkitScheduler}，因此同一份代码在 Folia 上行为一致。</p>
 *
 * @author JunXieX
 */
public final class Tasks {
    private final Plugin plugin;
    private final Set<ScheduledTask> handles = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
    }


    /** 全局区域延迟执行（单位 tick）。 */
    public void globalLater(Runnable action, long delayTicks) {
        if (closed || action == null) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            handles.remove(task);
            safe(action);
        }, Math.max(1L, delayTicks));
    }

    /** 全局区域周期任务，返回的任务句柄需要自行取消。 */
    public ScheduledTask globalTimer(Runnable action, long initialDelayTicks, long periodTicks) {
        if (closed || action == null) {
            return null;
        }
        ScheduledTask task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                ignored -> safe(action),
                Math.max(1L, initialDelayTicks),
                Math.max(1L, periodTicks));
        if (task != null) {
            handles.add(task);
        }
        return task;
    }


    /** 异步延迟执行（单位毫秒）。 */
    public void asyncLater(Runnable action, long delayMillis) {
        if (closed || action == null) {
            return;
        }
        Bukkit.getAsyncScheduler().runDelayed(plugin, task -> {
            handles.remove(task);
            safe(action);
        }, Math.max(1L, delayMillis), TimeUnit.MILLISECONDS);
    }

    /**
     * 把操作投递到玩家所属区域线程；已经在正确线程时直接执行，避免多余的一 tick 延迟。
     */
    public void runFor(Player player, Runnable action) {
        if (closed || player == null || action == null) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            safe(action);
            return;
        }
        player.getScheduler().execute(plugin, () -> safe(action), null, 0L);
    }

    /** 把操作投递到实体所属区域线程。 */
    public void runFor(Entity entity, Runnable action) {
        if (closed || entity == null || action == null) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            safe(action);
            return;
        }
        entity.getScheduler().execute(plugin, () -> safe(action), null, 0L);
    }

    /** 取消单个任务。 */
    public void cancel(ScheduledTask task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (RuntimeException ignored) {
            // 任务已经结束或所属插件正在卸载，忽略即可
        }
        handles.remove(task);
    }

    /** 关闭调度：取消全部任务并拒绝后续提交。 */
    public void shutdown() {
        closed = true;
        for (ScheduledTask task : handles) {
            try {
                task.cancel();
            } catch (RuntimeException ignored) {
                // 同上
            }
        }
        handles.clear();
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }

    private void safe(Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            plugin.getLogger().log(Level.WARNING, "调度任务执行失败", error);
        }
    }
}
