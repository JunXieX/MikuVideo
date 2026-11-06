package dev.junxiex.mikuvideo.playback;

import dev.junxiex.mikuvideo.config.Settings;
import dev.junxiex.mikuvideo.protocol.Playback;
import dev.junxiex.mikuvideo.screen.Screen;
import dev.junxiex.mikuvideo.screen.ScreenManager;
import dev.junxiex.mikuvideo.util.Tasks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 服务端权威播放时间线。
 *
 * <p>服务端从不下载或代理视频，只维护「从哪个时间点、以什么速率、是否暂停」这组状态，
 * 客户端按 {@code position + (now - startEpoch) * rate} 自行推算进度。</p>
 *
 * <p>起播缓冲用一次性延迟任务实现，并用自增令牌取消被后续操作覆盖的旧任务，
 * 因此连点播放不会出现“后一块屏幕把前一块提前开起来”的问题。</p>
 *
 * @author JunXieX
 */
public final class PlaybackService implements ScreenManager.Listener {

    /** seek 的最大目标位置：24 小时。 */
    public static final long MAX_SEEK_MILLIS = 24L * 60L * 60L * 1000L;

    /** 状态变更的对外广播口，由同步层实现。 */
    public interface Broadcaster {
        Broadcaster NONE = new Broadcaster() {
            @Override
            public void playbackChanged(Screen screen, Playback playback) {
            }

            @Override
            public void screenChanged(Screen screen) {
            }

            @Override
            public boolean hasNearbyViewer(Screen screen) {
                return false;
            }
        };

        /** 播放状态变化。 */
        void playbackChanged(Screen screen, Playback playback);

        /** 屏幕本身（尺寸 / 可见性 / 音量字段）变化。 */
        void screenChanged(Screen screen);

        /** 该屏幕附近是否还有客户端，用于跳过无人时的周期纠偏。 */
        boolean hasNearbyViewer(Screen screen);
    }

    private final ScreenManager screens;
    private final Supplier<Settings> settings;
    private final Tasks tasks;
    private final Map<String, Playback> states = new ConcurrentHashMap<>();
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private final Map<String, Deque<QueuedMedia>> queues = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile Broadcaster broadcaster = Broadcaster.NONE;
    private volatile boolean closed;

    public PlaybackService(ScreenManager screens, Supplier<Settings> settings, Tasks tasks) {
        this.screens = screens;
        this.settings = settings == null ? Settings::defaults : settings;
        this.tasks = tasks;
    }

    public void setBroadcaster(Broadcaster broadcaster) {
        this.broadcaster = broadcaster == null ? Broadcaster.NONE : broadcaster;
    }

    /** 当前状态，未知屏幕返回“已停止”。 */
    public Playback state(String screenId) {
        Playback state = screenId == null ? null : states.get(screenId);
        return state == null ? Playback.stopped() : state;
    }

    /** 取状态，缺失时按屏幕音量初始化一条“已停止”记录。 */
    public Playback stateOf(Screen screen) {
        if (screen == null) {
            return Playback.stopped();
        }
        return states.computeIfAbsent(screen.id(), id -> Playback.stopped().withVolume(screen.volume()));
    }

    /** 全量快照，供持久化使用。 */
    public Map<String, Playback> snapshot() {
        return Map.copyOf(states);
    }

    /** 载入阶段导入历史状态。 */
    public void importState(String screenId, Playback playback) {
        if (screenId == null || playback == null) {
            return;
        }
        states.put(screenId, normalize(playback));
    }

    /**
     * 恢复时的状态规整。
     *
     * <p>停机期间时间线仍在按 startEpoch 推算，如果恢复时已经越过片尾，
     * 与其让客户端跳到结尾，不如停在结尾等待重新播放。</p>
     */
    private Playback normalize(Playback state) {
        if (state.paused() || state.isStopped() || state.durationMillis() <= 0L) {
            return state;
        }
        long now = System.currentTimeMillis();
        if (state.positionAt(now) < state.durationMillis()) {
            return state;
        }
        return new Playback(state.url(), state.headers(), state.durationMillis(), now,
                true, state.rate(), state.volume(), false, state.durationMillis());
    }

    /**
     * 播放新链接：先置为暂停态并广播，缓冲 {@code start-hold-ms} 后再置为播放态。
     */
    public void play(Screen screen, String url, Map<String, String> headers) {
        if (screen == null || closed) {
            return;
        }
        long now = System.currentTimeMillis();
        Playback next = new Playback(url, headers, 0L, now, true, 1.0F,
                screen.volume(), true, 0L);
        states.put(screen.id(), next);
        long token = bump(screen.id());
        broadcaster.playbackChanged(screen, next);
        long holdMillis = settings.get().sync().startHoldMs();
        if (holdMillis <= 0L) {
            beginPlayback(screen.id(), token);
        } else {
            tasks.globalLater(() -> beginPlayback(screen.id(), token), Math.max(1L, holdMillis / 50L));
        }
    }

    /** 暂停：把推算出的当前位置固化下来。 */
    public boolean pause(Screen screen) {
        if (screen == null) {
            return false;
        }
        Playback current = states.get(screen.id());
        if (current == null || current.isStopped()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Playback paused = new Playback(current.url(), current.headers(), current.positionAt(now), now,
                true, current.rate(), current.volume(), current.audioEnabled(), current.durationMillis());
        states.put(screen.id(), paused);
        bump(screen.id());
        broadcaster.playbackChanged(screen, paused);
        return true;
    }

    /** 继续：以暂停位置为起点重新计时。 */
    public boolean resume(Screen screen) {
        if (screen == null) {
            return false;
        }
        Playback current = states.get(screen.id());
        if (current == null || current.isStopped()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Playback resumed = new Playback(current.url(), current.headers(), current.positionMillis(), now,
                false, current.rate(), current.volume(), true, current.durationMillis());
        states.put(screen.id(), resumed);
        bump(screen.id());
        broadcaster.playbackChanged(screen, resumed);
        return true;
    }

    /** 跳转，暂停状态下同样允许。 */
    public boolean seek(Screen screen, double seconds) {
        if (screen == null) {
            return false;
        }
        Playback current = states.get(screen.id());
        if (current == null || current.isStopped()) {
            return false;
        }
        long limit = current.durationMillis() > 0L ? current.durationMillis() : MAX_SEEK_MILLIS;
        long target = Math.round(Math.max(0.0D, seconds) * 1000.0D);
        target = Math.max(0L, Math.min(target, limit));
        long now = System.currentTimeMillis();
        Playback moved = new Playback(current.url(), current.headers(), target, now,
                current.paused(), current.rate(), current.volume(), current.audioEnabled(), current.durationMillis());
        states.put(screen.id(), moved);
        bump(screen.id());
        broadcaster.playbackChanged(screen, moved);
        return true;
    }

    /** 停止：清空链接与进度，保留音量。 */
    public boolean stop(Screen screen) {
        if (screen == null) {
            return false;
        }
        Playback stopped = Playback.stopped().withVolume(screen.volume());
        states.put(screen.id(), stopped);
        bump(screen.id());
        broadcaster.playbackChanged(screen, stopped);
        return true;
    }

    /** 音量：同时更新播放状态与屏幕信息里的音量字段。 */
    public boolean volume(Screen screen, int volume) {
        if (screen == null) {
            return false;
        }
        int value = Math.max(0, Math.min(100, volume));
        Screen updated = screens.replace(screen.withVolume(value)).orElse(screen);
        Playback current = states.computeIfAbsent(updated.id(),
                id -> Playback.stopped().withVolume(updated.volume()));
        Playback changed = current.withVolume(value);
        states.put(updated.id(), changed);
        // 屏幕本身的变化由 ScreenManager 的监听者统一广播，这里只补播放状态
        broadcaster.playbackChanged(updated, changed);
        return true;
    }

    /**
     * 记录客户端上报的时长：同一链接只接受第一次上报。
     *
     * @return 是否真的写入
     */
    public boolean reportDuration(Screen screen, long durationMillis) {
        if (screen == null || closed || durationMillis <= 0L) {
            return false;
        }
        long clamped = Math.min(durationMillis, MAX_SEEK_MILLIS);
        Playback current = states.get(screen.id());
        if (current == null || current.isStopped() || current.durationMillis() > 0L) {
            return false;
        }
        Playback updated = current.withDuration(clamped);
        states.put(screen.id(), updated);
        broadcaster.playbackChanged(screen, updated);
        return true;
    }

    /** 周期纠偏：重播时间线，并在播放到片尾时自动切队列下一项。 */
    public void rebroadcast() {
        if (closed) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Playback> entry : states.entrySet()) {
            Playback state = entry.getValue();
            if (state.isStopped() || state.paused()) {
                continue;
            }
            Optional<Screen> screen = screens.get(entry.getKey());
            if (screen.isEmpty()) {
                continue;
            }
            Screen target = screen.get();
            if (state.durationMillis() > 0L && state.positionAt(now) >= state.durationMillis() - 250L) {
                if (queueNext(target)) {
                    continue;
                }
                if (target.loop()) {
                    restart(target, state, now);
                    continue;
                }
                finish(target, state, now);
                continue;
            }
            if (!broadcaster.hasNearbyViewer(target)) {
                continue;
            }
            broadcaster.playbackChanged(target, state);
        }
    }

    /** 循环播放：回到起点并以当前时刻重新计时。 */
    private void restart(Screen screen, Playback state, long now) {
        Playback restarted = new Playback(state.url(), state.headers(), 0L, now,
                false, state.rate(), state.volume(), state.audioEnabled(), state.durationMillis());
        states.put(screen.id(), restarted);
        bump(screen.id());
        broadcaster.playbackChanged(screen, restarted);
    }

    /** 非循环播放到结尾：停在片尾，避免客户端反复触发结束逻辑。 */
    private void finish(Screen screen, Playback state, long now) {
        long duration = Math.max(0L, state.durationMillis());
        Playback ended = new Playback(state.url(), state.headers(), duration, now,
                true, state.rate(), state.volume(), false, duration);
        states.put(screen.id(), ended);
        bump(screen.id());
        broadcaster.playbackChanged(screen, ended);
    }

    /** 队列项。 */
    public record QueuedMedia(String url, Map<String, String> headers) {
        public QueuedMedia {
            url = url == null ? "" : url;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /** 追加队列。 */
    public boolean queueAdd(Screen screen, String url, Map<String, String> headers) {
        if (screen == null || url == null || url.isBlank()) {
            return false;
        }
        Deque<QueuedMedia> queue = queues.computeIfAbsent(screen.id(), key -> new ArrayDeque<>());
        synchronized (queue) {
            if (queue.size() >= 64) {
                return false;
            }
            queue.addLast(new QueuedMedia(url, headers));
        }
        return true;
    }

    /** 队列快照。 */
    public List<QueuedMedia> queueList(String screenId) {
        Deque<QueuedMedia> queue = screenId == null ? null : queues.get(screenId);
        if (queue == null) {
            return List.of();
        }
        synchronized (queue) {
            return List.copyOf(queue);
        }
    }

    /** 清空队列。 */
    public int queueClear(String screenId) {
        Deque<QueuedMedia> queue = screenId == null ? null : queues.remove(screenId);
        if (queue == null) {
            return 0;
        }
        synchronized (queue) {
            int size = queue.size();
            queue.clear();
            return size;
        }
    }

    /** 播放下一项；没有队列时返回 false。 */
    public boolean queueNext(Screen screen) {
        if (screen == null) {
            return false;
        }
        Deque<QueuedMedia> queue = queues.get(screen.id());
        if (queue == null) {
            return false;
        }
        QueuedMedia next;
        synchronized (queue) {
            next = queue.pollFirst();
            if (queue.isEmpty()) {
                queues.remove(screen.id(), queue);
            }
        }
        if (next == null) {
            return false;
        }
        play(screen, next.url(), next.headers());
        return true;
    }

    /** 载入阶段恢复队列（最多 64 项）。 */
    public int restoreQueue(String screenId, List<QueuedMedia> items) {
        if (screenId == null || items == null || items.isEmpty()) {
            return 0;
        }
        Deque<QueuedMedia> queue = queues.computeIfAbsent(screenId, key -> new ArrayDeque<>());
        int added = 0;
        synchronized (queue) {
            for (QueuedMedia item : items) {
                if (item == null || item.url().isBlank()) {
                    continue;
                }
                if (queue.size() >= 64) {
                    break;
                }
                queue.addLast(item);
                added++;
            }
        }
        return added;
    }

    /** 屏幕删除时清理队列。 */
    public void forgetQueue(String screenId) {
        if (screenId != null) {
            queues.remove(screenId);
        }
    }

    /** 屏幕被删除时清理状态。 */
    public void forget(String screenId) {
        if (screenId == null) {
            return;
        }
        states.remove(screenId);
        tokens.remove(screenId);
        queues.remove(screenId);
    }

    /** 关服清理。 */
    public void shutdown() {
        closed = true;
        states.clear();
        tokens.clear();
        broadcaster = Broadcaster.NONE;
    }

    private void beginPlayback(String screenId, long token) {
        if (closed) {
            return;
        }
        Long current = tokens.get(screenId);
        if (current == null || current.longValue() != token) {
            // 期间被暂停 / seek / 停止覆盖，放弃这次起播
            return;
        }
        Optional<Screen> screen = screens.get(screenId);
        if (screen.isEmpty()) {
            return;
        }
        Playback state = states.get(screenId);
        if (state == null || state.isStopped()) {
            return;
        }
        long now = System.currentTimeMillis();
        Playback started = new Playback(state.url(), state.headers(), state.positionMillis(), now,
                false, state.rate(), state.volume(), true, state.durationMillis());
        states.put(screenId, started);
        broadcaster.playbackChanged(screen.get(), started);
    }

    @Override
    public void onScreenRemoved(Screen screen) {
        if (screen != null) {
            forget(screen.id());
        }
    }

    private long bump(String screenId) {
        long token = sequence.incrementAndGet();
        tokens.put(screenId, token);
        return token;
    }
}