package dev.junxiex.mikuvideo.client.media;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 以外部 ffmpeg 进程为后端的解码引擎。
 *
 * <p>一个引擎可以创建任意多个会话（每块屏幕一个），所有会话共享同一份 ffmpeg 路径与版本信息。
 * 引擎本身不做任何 Minecraft 相关操作，纯 JDK 实现，可单独测试。</p>
 *
 * <p>线程安全：除了首次定位会短暂探测一次外，所有方法都不阻塞调用线程；
 * {@link #downloadAsync(Consumer)} 的结果在后台线程完成。
 * 定位结果会被缓存，因此 {@link #isAvailable()} 与 {@link #statusText()} 在第一次调用之后是纯读取。</p>
 *
 * <p>下载是显式行为：默认只在本地查找；打开 {@link #setAutoDownload(boolean)} 后，
 * 在"找不到 ffmpeg"时会在后台尝试一次下载，前台调用仍立即返回 {@code null} / {@code false}。</p>
 *
 * @author JunXieX
 */
public final class FfmpegMediaEngine implements MediaEngine {

    /** 下载进度，{@code total} 未知时为 -1。 */
    public record Progress(long done, long total) {
    }

    private final FfmpegLocator locator;
    private final FfmpegDownloader downloader;
    private final AtomicInteger sessionSeq = new AtomicInteger();
    private final Object downloadLock = new Object();

    private volatile String downloadFailure = "";
    private volatile boolean autoDownload;
    private volatile CompletableFuture<Path> autoDownloadFuture;
    /** 自动下载只尝试一次，失败后等玩家手动重试。 */
    private volatile boolean downloadAttempted;

    /** 以当前工作目录作为游戏目录。 */
    public FfmpegMediaEngine() {
        this(FfmpegLocator.defaultGameDir());
    }

    /** 以指定游戏目录（通常是 .minecraft）创建引擎。 */
    public FfmpegMediaEngine(Path gameDirectory) {
        this(new FfmpegLocator(gameDirectory));
    }

    /** 复用已有的定位器，便于在设置界面与引擎之间共享状态。 */
    public FfmpegMediaEngine(FfmpegLocator locator) {
        this.locator = locator == null ? new FfmpegLocator() : locator;
        this.downloader = new FfmpegDownloader(this.locator.bundleDir());
    }

    public FfmpegLocator locator() {
        return locator;
    }


    /** 当前实际使用的 ffmpeg，未找到时为 {@code null}。 */
    public Path executable() {
        Path found = locator.locate();
        if (found == null && autoDownload) {
            triggerAutoDownload();
        }
        return found;
    }

    /** 当前 ffmpeg 版本短串，未找到为空串。 */
    public String version() {
        return locator.version();
    }

    /** 设置首选 ffmpeg 路径（可执行文件或其所在目录），会清空定位缓存。 */
    public void setPreferredPath(String path) {
        locator.setPath(path);
    }


    /** 允许在找不到 ffmpeg 时后台自动下载一次；默认关闭。 */
    public void setAutoDownload(boolean enabled) {
        this.autoDownload = enabled;
    }


    /** 是否正在后台下载 ffmpeg；此时"找不到二进制"只是暂时状态。 */
    public boolean isDownloading() {
        CompletableFuture<Path> future = autoDownloadFuture;
        return future != null && !future.isDone();
    }

    /** 自动下载的完成信号；没有下载任务时返回已完成的 future。 */
    public CompletableFuture<Path> downloadFuture() {
        CompletableFuture<Path> future = autoDownloadFuture;
        return future == null ? CompletableFuture.completedFuture(null) : future;
    }

    /** 清空定位缓存，下次使用时重新按顺序探测。 */
    public void reload() {
        locator.reload();
    }


    /**
     * 在后台下载并解压 ffmpeg，完成后把路径交给定位器。
     *
     * <p>并发调用是安全的：下载器内部串行化，后到的调用会直接拿到同一份文件。
     * 进度回调在下载线程执行，回调里抛出的异常会被吞掉，不影响下载本身。</p>
     */
    public CompletableFuture<Path> downloadAsync(Consumer<Progress> listener) {
        CompletableFuture<Path> future = new CompletableFuture<>();
        MediaThreads.daemon("download", () -> {
            try {
                FfmpegDownloader.ProgressSink sink = listener == null ? null
                        : (done, total) -> {
                    try {
                        listener.accept(new Progress(done, total));
                    } catch (Throwable ignored) {
                        // 回调异常不影响下载
                    }
                };
                FfmpegDownloader worker = sink == null
                        ? downloader
                        : new FfmpegDownloader(locator.bundleDir(), sink);
                Path binary = worker.downloadIfMissing();
                locator.setPath(binary.toString());
                downloadFailure = "";
                future.complete(binary);
            } catch (Throwable t) {
                downloadFailure = t.getMessage() == null ? t.toString() : t.getMessage();
                future.completeExceptionally(t);
            }
        }).start();
        return future;
    }


    @Override
    public MediaSession createSession() {
        return new FfmpegMediaSession(this, "s" + sessionSeq.incrementAndGet());
    }

    @Override
    public boolean isAvailable() {
        return executable() != null;
    }

    @Override
    public String statusText() {
        Path found = executable();
        if (found != null) {
            String version = locator.version();
            String text = "ffmpeg " + (version.isEmpty() ? "(版本未知)" : version) + "  " + found;
            if (!downloadFailure.isEmpty()) {
                text = text + "（上次下载失败：" + downloadFailure + "）";
            }
            return text;
        }
        StringBuilder builder = new StringBuilder("未找到 ffmpeg");
        String reason = locator.failureReason();
        if (reason != null && !reason.isEmpty()) {
            builder.append("（").append(reason).append("）");
        }
        builder.append("；可安装到系统 PATH、放到 ").append(locator.bundleDir())
                .append("，或在设置中手动指定路径");
        if (!FfmpegDownloader.isSupported()) {
            builder.append("；").append(FfmpegDownloader.unsupportedReason());
        }
        if (!downloadFailure.isEmpty()) {
            builder.append("；上次下载失败：").append(downloadFailure);
        }
        return builder.toString();
    }

    private void triggerAutoDownload() {
        synchronized (downloadLock) {
            // 失败过的自动下载不再重试：调用方（executable/statusText）是热路径，
            // 重试会变成「失败即重下」的死循环，手动 download 命令才是重试入口。
            if (downloadAttempted) {
                return;
            }
            downloadAttempted = true;
            autoDownloadFuture = downloadAsync(null);
        }
    }
}