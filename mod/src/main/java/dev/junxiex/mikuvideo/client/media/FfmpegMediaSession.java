package dev.junxiex.mikuvideo.client.media;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用外部 ffmpeg 进程解码的会话实现。
 *
 * <p>每个会话最多跑两个进程：视频进程输出定尺寸 RGBA 裸帧，音频进程输出 48kHz/16bit/双声道 PCM。
 * 拆成两个进程的原因是视频端要做恒帧率节流，音频端要被声卡直接反压，
 * 两种节奏放在同一个管道里会互相拖死。</p>
 *
 * <p>线程模型：</p>
 * <ul>
 *   <li>控制线程：串行化 start/pause/resume/seek/stop 等控制动作，保证状态机不被并发撕裂；</li>
 *   <li>视频读线程：按帧率节流、落后丢帧、调用 {@link VideoFrameSink}；</li>
 *   <li>音频读线程：读取 PCM、做软件音量、写 {@link SourceDataLine}；</li>
 *   <li>stderr 排空线程：每个进程一个，缓存最后 8 行用于错误归类。</li>
 * </ul>
 *
 * <p>暂停采用"停止读取"而不是"丢弃帧"：读线程不再读管道，管道写满后 ffmpeg 自己阻塞，
 * CPU 占用降到接近 0；恢复时继续读，声卡仍由 {@code line.stop()/start()} 控制。</p>
 *
 * @author JunXieX
 */
public final class FfmpegMediaSession implements MediaSession {

    /** 音频采样率。 */
    static final int AUDIO_RATE = 48_000;
    /** 音频声道数。 */
    static final int AUDIO_CHANNELS = 2;
    /** s16le 双声道每帧字节数。 */
    private static final int AUDIO_BYTES_PER_SAMPLE_FRAME = AUDIO_CHANNELS * 2;
    /** 声卡缓冲约 100ms，兼顾延迟与爆音。 */
    private static final int AUDIO_LINE_BYTES = (AUDIO_RATE / 10) * AUDIO_BYTES_PER_SAMPLE_FRAME;
    /** 单次从 ffmpeg 读取的 PCM 块大小。 */
    private static final int AUDIO_CHUNK_BYTES = 8_192;

    /** 落后超过该阈值就丢帧追赶。 */
    private static final long DROP_LATE_NANOS = 150_000_000L;
    /** 暂停超过该时长释放解码进程；可用系统属性 {@code mikuvideo.pauseReleaseSeconds} 调整。 */
    private static final long PAUSE_RELEASE_SECONDS =
            Long.getLong("mikuvideo.pauseReleaseSeconds", 30L);
    /** 销毁进程的优雅等待时间。 */
    private static final long DESTROY_GRACE_MS = 500L;
    /** 读线程 join 上限。 */
    private static final long JOIN_MS = 800L;
    /** stderr 保留行数。 */
    private static final int STDERR_LINES = 8;
    /** 节流等待的最大切片，保证暂停/停止能被及时感知。 */
    private static final long PARK_SLICE_NANOS = 20_000_000L;
    /** 时长探测的超时时间。 */
    private static final long PROBE_TIMEOUT_MS = 10_000L;
    /** 时长探测的输出上限。 */
    private static final int PROBE_MAX_BYTES = 64 * 1024;
    /** ffmpeg 输入信息里的时长行。 */
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    /**
     * 跨会话共享的定时器，只用来跑"长暂停释放"这类超时任务。
     *
     * <p>核心线程允许超时退出：没有待执行任务时它会自行结束，
     * 因此最后一个会话关闭后不会留下常驻线程。</p>
     */
    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    /**
     * 存活解码进程登记表。
     *
     * <p>会话的关闭是异步的；万一客户端在清理完成前直接退出 JVM，ffmpeg 子进程会变成孤儿进程
     * 一直占着 CPU。这里在 JVM 退出时统一强杀，作为最后一道兜底（只在退出线程执行，不阻塞业务线程）。</p>
     */
    private static final Set<Process> LIVE_PROCESSES = ConcurrentHashMap.newKeySet();

    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (Process process : LIVE_PROCESSES) {
                    try {
                        process.destroyForcibly();
                    } catch (Throwable ignored) {
                        // 退出阶段尽力而为
                    }
                }
                LIVE_PROCESSES.clear();
            }, "mikuvideo-shutdown"));
        } catch (IllegalStateException ignored) {
            // JVM 正在关闭时注册钩子会抛异常，此时无需再兜底
        } catch (SecurityException ignored) {
            // 受限环境不允许注册钩子
        }
    }

    private static ScheduledThreadPoolExecutor createTimer() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "mikuvideo-timer");
            thread.setDaemon(true);
            return thread;
        });
        executor.setKeepAliveTime(2L, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final FfmpegMediaEngine engine;
    private final String tag;
    private final ExecutorService control;

    private final Object stateLock = new Object();
    private final AtomicInteger runSeq = new AtomicInteger();

    private volatile MediaRequest request;
    private volatile VideoFrameSink sink;
    private volatile MediaStateListener listener;

    private volatile MediaState state = MediaState.IDLE;
    private volatile String stateDetail = "";
    private volatile String lastError = "";
    private volatile int lastErrorCode = FfmpegError.NONE;

    private volatile float volume = 1.0f;
    private volatile boolean paused;
    private volatile boolean closed;

    private volatile Run run;
    private volatile long basePositionMs;
    private final AtomicLong consumedFrames = new AtomicLong();
    private volatile ScheduledFuture<?> pauseRelease;

    private volatile long durationMs;
    private volatile String durationUrl = "";
    private final AtomicInteger probeSeq = new AtomicInteger();
    private volatile Process probeProcess;

    FfmpegMediaSession(FfmpegMediaEngine engine, String tag) {
        this.engine = engine;
        this.tag = tag == null ? "s" : tag;
        this.control = Executors.newSingleThreadExecutor(MediaThreads.factory("ctl-" + this.tag));
    }

    // ------------------------------------------------------------------
    // MediaSession
    // ------------------------------------------------------------------

    @Override
    public void start(MediaRequest request, VideoFrameSink sink, MediaStateListener listener) {
        if (request == null) {
            return;
        }
        this.sink = sink;
        this.listener = listener;
        // 已经确认没有 ffmpeg 且当前没有解码在跑：立刻回调，不排队等控制线程
        if (run == null && engine.locator().isResolved() && engine.executable() == null) {
            this.request = request;
            this.paused = false;
            this.basePositionMs = request.startPositionMillis();
            this.consumedFrames.set(0L);
            this.durationMs = 0L;
            this.durationUrl = "";
            reportMissingFfmpeg();
            return;
        }
        submit("start", () -> {
            if (closed) {
                return;
            }
            releasePauseTimer();
            this.sink = sink;
            this.listener = listener;
            this.request = request;
            this.paused = false;
            this.lastError = "";
            this.lastErrorCode = FfmpegError.NONE;

            Run previous = this.run;
            this.run = null;
            if (previous != null) {
                destroy(previous);
            }
            if (!Objects.equals(request.url(), durationUrl)) {
                durationMs = 0L;
                durationUrl = "";
            }
            this.basePositionMs = request.startPositionMillis();
            this.consumedFrames.set(0L);
            applyState(MediaState.PREPARING, "正在启动解码…");
            launch(request, this.basePositionMs);
        });
    }

    @Override
    public void setVolume(float volume) {
        float clamped = Math.max(0.0f, Math.min(1.0f, volume));
        this.volume = clamped;
        Run current = this.run;
        if (current != null) {
            SourceDataLine line = current.line;
            if (line != null) {
                applyGain(line, clamped);
            }
        }
    }

    @Override
    public void pause() {
        submit("pause", () -> {
            if (closed || paused) {
                return;
            }
            if (state == MediaState.IDLE || state == MediaState.ERROR) {
                return;
            }
            paused = true;
            Run current = this.run;
            if (current != null) {
                SourceDataLine line = current.line;
                if (line != null) {
                    stopLine(line);
                    flushLine(line);
                }
            }
            applyState(MediaState.PAUSED, "已暂停");
            schedulePauseRelease();
        });
    }

    @Override
    public void resume() {
        submit("resume", () -> {
            if (closed || !paused) {
                return;
            }
            paused = false;
            releasePauseTimer();
            Run current = this.run;
            if (current == null) {
                MediaRequest req = this.request;
                if (req == null) {
                    return;
                }
                long position = positionMillis();
                this.basePositionMs = position;
                this.consumedFrames.set(0L);
                applyState(MediaState.PREPARING, "恢复播放，正在重新定位…");
                launch(req, position);
                return;
            }
            SourceDataLine line = current.line;
            if (line != null) {
                startLine(line);
            }
            synchronized (current.gate) {
                current.gate.notifyAll();
            }
            applyState(MediaState.PLAYING, "继续播放");
        });
    }

    @Override
    public void seek(long positionMillis) {
        submit("seek", () -> {
            if (closed) {
                return;
            }
            MediaRequest req = this.request;
            if (req == null) {
                return;
            }
            long target = Math.max(0L, positionMillis);
            releasePauseTimer();
            paused = false;
            Run previous = this.run;
            this.run = null;
            if (previous != null) {
                destroy(previous);
            }
            this.basePositionMs = target;
            this.consumedFrames.set(0L);
            applyState(MediaState.PREPARING, "定位到 " + formatSeconds(target) + "…");
            launch(req, target);
        });
    }

    /**
     * 停止播放。
     *
     * <p>只把清理任务投进控制线程队列就返回，永不阻塞调用线程：客户端主线程批量关闭屏幕时
     * 不能因为等进程退出而掉帧。排在本方法之后到达的 {@link #start(MediaRequest, VideoFrameSink, MediaStateListener)}
     * 会排在同一队列后面，因此"先停后起"的顺序天然成立。</p>
     */
    @Override
    public void stop() {
        if (closed) {
            return;
        }
        try {
            control.execute(this::teardown);
        } catch (RejectedExecutionException ignored) {
            // 执行器已关闭
        }
    }

    /**
     * 关闭会话。
     *
     * <p>幂等且不阻塞：只置标记、取消定时器、把清理任务投进控制线程队列，然后关闭执行器
     * （已排队的任务仍会跑完）。真正的进程销毁、管道关闭、读线程 join 全部在控制线程完成，
     * 因此主线程可以在一个 tick 内关掉几十块屏幕。</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        releasePauseTimer();
        try {
            control.execute(this::teardown);
        } catch (RejectedExecutionException ignored) {
            // 执行器已经关闭
        }
        control.shutdown();
    }

    @Override
    public MediaState state() {
        return state;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    // ------------------------------------------------------------------
    // 供上层读取的附加信息
    // ------------------------------------------------------------------

    /** 最近一次状态变化的说明文字，可能包含降级提示。 */
    public String stateDetail() {
        return stateDetail;
    }

    /** 最近一次错误的错误码，见 {@link FfmpegError}。 */
    public int lastErrorCode() {
        return lastErrorCode;
    }

    /**
     * 当前播放位置（毫秒）。
     *
     * <p>由"定位基准 + 已消费帧数 / 帧率"推算，暂停时不推进；被丢弃的帧仍然推进时间轴，
     * 因为它们已经被解码消费掉了，位置必须跟上内容而不是停在被丢的那一帧。</p>
     */
    public long positionMillis() {
        MediaRequest req = this.request;
        int fps = req == null ? 30 : req.fps();
        long frames = consumedFrames.get();
        return basePositionMs + (frames * 1000L) / Math.max(1, fps);
    }

    /**
     * 媒体总时长（毫秒）。
     *
     * <p>由一次独立的 {@code ffmpeg -i} 在后台解析 "Duration:" 行得到，探测失败或直播流返回 0。
     * 本方法只读缓存，永不阻塞调用线程。</p>
     */
    @Override
    public long durationMillis() {
        return durationMs;
    }

    /** 当前 run 已呈现的帧数。 */
    public long presentedFrames() {
        Run current = this.run;
        return current == null ? 0L : current.presented.get();
    }

    /** 当前 run 因落后而丢弃的帧数。 */
    public long droppedFrames() {
        Run current = this.run;
        return current == null ? 0L : current.dropped.get();
    }

    // ------------------------------------------------------------------
    // 启动与进程生命周期
    // ------------------------------------------------------------------

    /** 启动一轮解码：视频进程必有，音频进程按需。必须在控制线程调用。 */
    private void launch(MediaRequest req, long startMs) {
        Path ffmpeg = engine.executable();
        if (ffmpeg == null) {
            reportMissingFfmpeg();
            return;
        }
        int width = req.maxWidth();
        int height = req.maxHeight();
        int fps = req.fps();

        Run created = new Run(runSeq.incrementAndGet(), width, height, fps);
        Process videoProcess;
        try {
            List<String> args = FfmpegCommands.video(ffmpeg, req, width, height, fps, startMs);
            videoProcess = startProcess(args);
            created.videoProcess = videoProcess;
            created.videoIn = videoProcess.getInputStream();
        } catch (IOException e) {
            lastErrorCode = FfmpegError.PROCESS;
            lastError = FfmpegError.format(FfmpegError.PROCESS, "启动视频解码进程失败：" + e.getMessage());
            applyState(MediaState.ERROR, lastError);
            return;
        }

        this.run = created;
        startDurationProbe(req);
        created.videoErrThread = MediaThreads.daemon("vstderr-" + created.id,
                () -> drainStderr(videoProcess, created.videoStderr));
        created.videoErrThread.start();
        created.videoThread = MediaThreads.daemon("video-" + created.id, () -> pumpVideo(created));
        created.videoThread.start();

        if (req.audioEnabled()) {
            startAudio(created, req, startMs);
        }
    }

    private void startAudio(Run created, MediaRequest req, long startMs) {
        Path ffmpeg = engine.executable();
        if (ffmpeg == null) {
            return;
        }
        Process audioProcess;
        try {
            List<String> args = FfmpegCommands.audio(ffmpeg, req, startMs);
            audioProcess = startProcess(args);
            created.audioProcess = audioProcess;
            created.audioIn = audioProcess.getInputStream();
        } catch (IOException e) {
            created.audioNote = "音频进程启动失败，已静音播放";
            return;
        }
        created.audioErrThread = MediaThreads.daemon("astderr-" + created.id,
                () -> drainStderr(audioProcess, created.audioStderr));
        created.audioErrThread.start();
        created.audioThread = MediaThreads.daemon("audio-" + created.id, () -> pumpAudio(created));
        created.audioThread.start();
    }

    // ------------------------------------------------------------------
    // 视频读线程
    // ------------------------------------------------------------------

    private void pumpVideo(Run current) {
        ReadableByteChannel channel = Channels.newChannel(current.videoIn);
        ByteBuffer buffer = current.videoBuffer;
        long interval = 1_000_000_000L / Math.max(1, current.fps);
        long nextDue = 0L;
        boolean first = true;
        boolean announced = false;
        try {
            while (!current.stop) {
                if (!awaitPlayable(current)) {
                    break;
                }
                buffer.clear();
                if (!readFully(channel, buffer)) {
                    break;
                }
                long now = System.nanoTime();
                if (first) {
                    first = false;
                    nextDue = now;
                }
                if (!waitUntil(current, nextDue)) {
                    break;
                }
                long late = System.nanoTime() - nextDue;
                current.consumed.incrementAndGet();
                consumedFrames.incrementAndGet();
                if (late > DROP_LATE_NANOS) {
                    // 落后太多：丢掉这一帧并立刻重新对齐，避免永远追不上
                    current.dropped.incrementAndGet();
                    nextDue = System.nanoTime();
                    continue;
                }
                buffer.flip();
                current.presented.incrementAndGet();
                deliver(current, buffer);
                if (!announced) {
                    announced = true;
                    announcePlaying(current);
                }
                nextDue += interval;
            }
        } catch (Throwable t) {
            if (!current.stop) {
                current.failure = t;
            }
        } finally {
            onVideoFinished(current);
        }
    }

    private void deliver(Run current, ByteBuffer frame) {
        VideoFrameSink target = this.sink;
        if (target == null) {
            return;
        }
        try {
            target.onFrame(frame, current.width, current.height);
        } catch (Throwable ignored) {
            // 上层渲染异常不能打死解码线程
        }
    }

    private void announcePlaying(Run current) {
        if (this.run != current) {
            return;
        }
        StringBuilder detail = new StringBuilder("播放中 ")
                .append(current.width).append('×').append(current.height)
                .append('@').append(current.fps);
        String note = current.audioNote;
        if (note != null && !note.isEmpty()) {
            detail.append("｜").append(note);
        }
        applyState(MediaState.PLAYING, detail.toString());
    }

    private void onVideoFinished(Run current) {
        if (current.stop || closed) {
            return;
        }
        submit("video-end", () -> handleVideoEnd(current));
    }

    private void handleVideoEnd(Run current) {
        if (this.run != current) {
            return;
        }
        this.run = null;
        int exit = waitExit(current.videoProcess, 600L);
        String tail = current.videoStderr.tail(STDERR_LINES);
        if (tail.isEmpty() && current.failure != null) {
            tail = String.valueOf(current.failure);
        }
        destroy(current);
        if (exit != Integer.MIN_VALUE && exit != 0) {
            lastErrorCode = FfmpegError.classify(tail);
            String detail = tail.isEmpty() ? "ffmpeg 退出码 " + exit : tail;
            lastError = FfmpegError.format(lastErrorCode, detail);
            applyState(MediaState.ERROR, lastError);
            return;
        }
        applyState(MediaState.PAUSED, "播放结束");
    }

    // ------------------------------------------------------------------
    // 音频读线程
    // ------------------------------------------------------------------

    private void pumpAudio(Run current) {
        InputStream input = current.audioIn;
        SourceDataLine line = null;
        try {
            AudioFormat format = new AudioFormat(AUDIO_RATE, 16, AUDIO_CHANNELS, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, AUDIO_LINE_BYTES);
            applyGain(line, volume);
            line.start();
            current.line = line;

            byte[] chunk = current.audioChunk;
            while (!current.stop && !closed) {
                if (!awaitPlayable(current)) {
                    break;
                }
                int read = input.read(chunk, 0, chunk.length);
                if (read <= 0) {
                    break;
                }
                float gain = volume;
                if (gain < 0.999f) {
                    scaleSamples(chunk, read, gain);
                }
                int offset = 0;
                while (offset < read && !current.stop) {
                    int written = line.write(chunk, offset, read - offset);
                    if (written <= 0) {
                        break;
                    }
                    offset += written;
                }
            }
        } catch (LineUnavailableException | IllegalArgumentException e) {
            if (!current.stop) {
                noteAudio(current, "无可用音频设备，已静音播放");
                destroyAudioProcess(current);
            }
        } catch (IOException e) {
            if (!current.stop) {
                String tail = current.audioStderr.tail(STDERR_LINES);
                if (!tail.isEmpty()) {
                    noteAudio(current, "音频中断（" + FfmpegError.compact(tail) + "）");
                }
            }
        } catch (Throwable t) {
            if (!current.stop) {
                noteAudio(current, "音频播放异常，已静音播放");
                destroyAudioProcess(current);
            }
        } finally {
            SourceDataLine owned = line;
            current.line = null;
            if (owned != null) {
                stopLine(owned);
                flushLine(owned);
                closeLine(owned);
            }
        }
    }

    private void noteAudio(Run current, String note) {
        current.audioNote = note;
        if (this.run == current && state == MediaState.PLAYING) {
            applyState(MediaState.PLAYING, "播放中 " + current.width + "×" + current.height
                    + "@" + current.fps + "｜" + note);
        }
    }

    // ------------------------------------------------------------------
    // 暂停 / 恢复 / 释放
    // ------------------------------------------------------------------

    /** 暂停时读线程阻塞在这里：只要不再读管道，ffmpeg 就会阻塞在写上，CPU 自然归零。 */
    private boolean awaitPlayable(Run current) {
        synchronized (current.gate) {
            while (paused && !current.stop && !closed) {
                try {
                    current.gate.wait(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return !current.stop && !closed;
    }

    /** 等到下一帧的呈现时刻；暂停期间不推进，返回 false 表示需要退出。 */
    private boolean waitUntil(Run current, long dueNanos) {
        while (!current.stop && !closed) {
            long remain = dueNanos - System.nanoTime();
            if (remain <= 0L) {
                return true;
            }
            if (paused) {
                if (!awaitPlayable(current)) {
                    return false;
                }
                continue;
            }
            if (!MediaThreads.park(Math.min(remain, PARK_SLICE_NANOS))) {
                return false;
            }
        }
        return false;
    }

    private void schedulePauseRelease() {
        releasePauseTimer();
        try {
            pauseRelease = TIMER.schedule(() -> {
                try {
                    control.execute(() -> {
                        if (closed || !paused) {
                            return;
                        }
                        Run current = this.run;
                        if (current == null) {
                            return;
                        }
                        this.run = null;
                        long position = positionMillis();
                        basePositionMs = position;
                        consumedFrames.set(0L);
                        destroy(current);
                        applyState(MediaState.PAUSED,
                                "长时间暂停，已释放解码进程（恢复时从 " + formatSeconds(position) + " 重新定位）");
                    });
                } catch (RejectedExecutionException ignored) {
                    // 会话已关闭
                }
            }, PAUSE_RELEASE_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // 定时器已关闭（不会发生）
        }
    }

    private void releasePauseTimer() {
        ScheduledFuture<?> pending = pauseRelease;
        pauseRelease = null;
        if (pending != null) {
            pending.cancel(false);
        }
    }

    private void teardown() {
        releasePauseTimer();
        paused = false;
        destroyProbe();
        Run current = this.run;
        this.run = null;
        if (current != null) {
            destroy(current);
        }
        consumedFrames.set(0L);
        applyState(MediaState.IDLE, "已停止");
    }

    /**
     * 彻底拆掉一轮解码：停声卡、关管道、destroy 进程、join 读线程。
     *
     * <p>顺序上先停音频再停视频：音频线程可能正阻塞在 {@code line.write}，
     * 先关 line 能让它立刻返回，不必等进程退出。</p>
     */
    private void destroy(Run current) {
        current.stop = true;
        synchronized (current.gate) {
            current.gate.notifyAll();
        }
        SourceDataLine line = current.line;
        current.line = null;
        if (line != null) {
            stopLine(line);
            flushLine(line);
            closeLine(line);
        }
        closeQuietly(current.videoIn);
        closeQuietly(current.audioIn);
        destroyProcess(current.audioProcess);
        destroyProcess(current.videoProcess);
        MediaThreads.join(current.audioThread, JOIN_MS);
        MediaThreads.join(current.videoThread, JOIN_MS);
        MediaThreads.join(current.audioErrThread, 300L);
        MediaThreads.join(current.videoErrThread, 300L);
    }

    private static void destroyProcess(Process process) {
        if (process == null) {
            return;
        }
        LIVE_PROCESSES.remove(process);
        try {
            OutputStream stdin = process.getOutputStream();
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // 进程可能已经退出
        }
        process.destroy();
        try {
            if (!process.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void destroyAudioProcess(Run current) {
        Process process = current.audioProcess;
        if (process == null) {
            return;
        }
        LIVE_PROCESSES.remove(process);
        try {
            process.destroy();
        } catch (RuntimeException ignored) {
            // 已经退出
        }
    }

    /** 启动解码进程并登记，便于 JVM 退出时兜底回收。 */
    private static Process startProcess(List<String> args) throws IOException {
        return startProcess(args, false);
    }

    private static Process startProcess(List<String> args, boolean mergeError) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(args);
        if (mergeError) {
            builder.redirectErrorStream(true);
        }
        Process process = builder.start();
        LIVE_PROCESSES.add(process);
        return process;
    }

    private static int waitExit(Process process, long millis) {
        if (process == null) {
            return Integer.MIN_VALUE;
        }
        try {
            if (process.waitFor(millis, TimeUnit.MILLISECONDS)) {
                return process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Integer.MIN_VALUE;
    }

    private static void drainStderr(Process process, StderrRing ring) {
        if (process == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ring.add(line);
            }
        } catch (IOException ignored) {
            // 进程被销毁时管道关闭，属正常路径
        }
    }

    // ------------------------------------------------------------------
    // 时长探测
    // ------------------------------------------------------------------

    private void reportMissingFfmpeg() {
        lastErrorCode = FfmpegError.BINARY_MISSING;
        lastError = FfmpegError.format(FfmpegError.BINARY_MISSING, engine.statusText());
        applyState(MediaState.ERROR, lastError);
    }

    /** 同一地址只探测一次；探测线程是守护线程，关闭会话时会被一起销毁。 */
    private void startDurationProbe(MediaRequest req) {
        String url = req.url();
        if (durationMs > 0L && Objects.equals(durationUrl, url)) {
            return;
        }
        Path ffmpeg = engine.executable();
        if (ffmpeg == null) {
            return;
        }
        int id = probeSeq.incrementAndGet();
        MediaThreads.daemon("probe-" + id, () -> {
            long millis = runDurationProbe(ffmpeg, req);
            MediaRequest current = this.request;
            if (millis > 0L && !closed && current != null && Objects.equals(current.url(), url)) {
                durationMs = millis;
                durationUrl = url;
            }
        }).start();
    }

    /** 跑一次 {@code ffmpeg -i}（不给输出文件），从输入信息里解析时长。 */
    private long runDurationProbe(Path ffmpeg, MediaRequest req) {
        List<String> args = new ArrayList<>(24);
        args.add(ffmpeg.toString());
        args.add("-hide_banner");
        args.add("-nostdin");
        args.add("-protocol_whitelist");
        args.add(FfmpegCommands.PROTOCOL_WHITELIST);
        boolean remote = FfmpegCommands.isRemote(req.url());
        if (remote) {
            String headers = FfmpegCommands.headerBlock(req.headers());
            if (!headers.isEmpty()) {
                args.add("-headers");
                args.add(headers);
            }
        }
        args.add("-rw_timeout");
        args.add(Long.toString(FfmpegCommands.RW_TIMEOUT_MICROS));
        if (remote) {
            args.add("-reconnect");
            args.add("1");
        }
        args.add("-i");
        args.add(req.url());

        Process process = null;
        try {
            process = startProcess(args, true);
            probeProcess = process;
            StringBuilder sink = new StringBuilder();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while (sink.length() < PROBE_MAX_BYTES && (read = input.read(buffer)) > 0) {
                    sink.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
            if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return 0L;
            }
            return parseDuration(sink.toString());
        } catch (IOException e) {
            return 0L;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0L;
        } finally {
            probeProcess = null;
            if (process != null) {
                LIVE_PROCESSES.remove(process);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }

    /** 解析 {@code Duration: 00:00:20.00}，直播流的 {@code N/A} 自然匹配不到，返回 0。 */
    static long parseDuration(String output) {
        if (output == null || output.isEmpty()) {
            return 0L;
        }
        Matcher matcher = DURATION_PATTERN.matcher(output);
        if (!matcher.find()) {
            return 0L;
        }
        long hours = Long.parseLong(matcher.group(1));
        long minutes = Long.parseLong(matcher.group(2));
        double seconds = Double.parseDouble(matcher.group(3));
        return (hours * 3600L + minutes * 60L) * 1000L + Math.round(seconds * 1000.0);
    }

    private void destroyProbe() {
        Process process = probeProcess;
        probeProcess = null;
        if (process != null) {
            try {
                process.destroy();
            } catch (RuntimeException ignored) {
                // 已退出
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void submit(String action, Runnable body) {
        if (closed) {
            return;
        }
        try {
            control.execute(() -> {
                try {
                    body.run();
                } catch (Throwable t) {
                    lastErrorCode = FfmpegError.PROCESS;
                    lastError = FfmpegError.format(FfmpegError.PROCESS, "内部错误：" + t);
                    applyState(MediaState.ERROR, lastError);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 会话已关闭
        }
    }

    private void applyState(MediaState next, String detail) {
        String text = detail == null ? "" : detail;
        MediaStateListener target;
        synchronized (stateLock) {
            if (state == next && stateDetail.equals(text)) {
                return;
            }
            state = next;
            stateDetail = text;
            target = listener;
        }
        if (target != null) {
            try {
                target.onStateChanged(next, text);
            } catch (Throwable ignored) {
                // 上层监听异常不能影响解码
            }
        }
    }

    private static boolean readFully(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                return false;
            }
        }
        return true;
    }

    private static void scaleSamples(byte[] data, int length, float gain) {
        for (int i = 0; i + 1 < length; i += 2) {
            int low = data[i] & 0xFF;
            int high = data[i + 1];
            int sample = (high << 8) | low;
            int scaled = (int) (sample * gain);
            if (scaled > 32_767) {
                scaled = 32_767;
            } else if (scaled < -32_768) {
                scaled = -32_768;
            }
            data[i] = (byte) scaled;
            data[i + 1] = (byte) (scaled >> 8);
        }
    }

    /**
     * 音量控制：0.5 以上只做软件线性缩放，0.5 以下交给声卡 MASTER_GAIN 衰减。
     *
     * <p>两条曲线在 0.5 处相接，因此 0..1 的听感是线性的，同时低音量不会因为 16bit
     * 量化而出现明显的台阶噪声。</p>
     */
    private static void applyGain(SourceDataLine line, float volume) {
        try {
            if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                return;
            }
            FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float target = volume >= 0.5f
                    ? 0.0f
                    : (float) (20.0 * Math.log10(Math.max(volume, 0.0001f) / 0.5));
            target = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), target));
            gain.setValue(target);
        } catch (RuntimeException ignored) {
            // 某些混音器在关闭瞬间会抛异常
        }
    }

    private static void startLine(SourceDataLine line) {
        try {
            line.start();
        } catch (RuntimeException ignored) {
            // 声卡已失效，音频线程会自行降级
        }
    }

    private static void stopLine(SourceDataLine line) {
        try {
            line.stop();
        } catch (RuntimeException ignored) {
            // 同上
        }
    }

    private static void flushLine(SourceDataLine line) {
        try {
            line.flush();
        } catch (RuntimeException ignored) {
            // 同上
        }
    }

    private static void closeLine(SourceDataLine line) {
        try {
            line.close();
        } catch (RuntimeException ignored) {
            // 同上
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // 进程已退出
        }
    }

    private static String formatSeconds(long millis) {
        long seconds = millis / 1000L;
        long fraction = millis % 1000L;
        return seconds + "." + String.format("%03d", fraction) + " 秒";
    }

    // ------------------------------------------------------------------
    // 一轮解码的运行期状态
    // ------------------------------------------------------------------

    /** 一次 start/seek 产生的完整解码上下文；结束时整体丢弃。 */
    private static final class Run {

        final int id;
        final int width;
        final int height;
        final int fps;

        /** 复用的帧缓冲，回调期间有效；不每帧分配。 */
        final ByteBuffer videoBuffer;

        final byte[] audioChunk = new byte[AUDIO_CHUNK_BYTES];
        final StderrRing videoStderr = new StderrRing(STDERR_LINES);
        final StderrRing audioStderr = new StderrRing(STDERR_LINES);

        final AtomicLong consumed = new AtomicLong();
        final AtomicLong presented = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();

        final Object gate = new Object();

        Process videoProcess;
        Process audioProcess;
        InputStream videoIn;
        InputStream audioIn;
        volatile SourceDataLine line;
        volatile boolean stop;
        volatile Throwable failure;
        volatile String audioNote = "";

        Thread videoThread;
        Thread audioThread;
        Thread videoErrThread;
        Thread audioErrThread;

        Run(int id, int width, int height, int fps) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.videoBuffer = allocate(width, height);
        }

        private static ByteBuffer allocate(int width, int height) {
            int size = width * height * 4;
            try {
                return ByteBuffer.allocateDirect(size);
            } catch (OutOfMemoryError error) {
                return ByteBuffer.allocate(size);
            }
        }
    }
}
