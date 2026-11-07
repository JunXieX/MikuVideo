package dev.junxiex.mikuvideo.client.screen;

import dev.junxiex.mikuvideo.client.config.ClientConfig;
import dev.junxiex.mikuvideo.client.media.MediaState;
import dev.junxiex.mikuvideo.client.media.resolve.ResolvedMedia;
import dev.junxiex.mikuvideo.client.net.ClockSync;
import dev.junxiex.mikuvideo.client.render.VideoTexture;
import dev.junxiex.mikuvideo.protocol.Playback;
import dev.junxiex.mikuvideo.protocol.ScreenInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.util.Locale;

/** 客户端持有的一块屏幕：几何、播放状态、视频帧与纹理。 */
public final class ClientScreen {
    private volatile ScreenInfo info;
    private volatile Playback playback = Playback.stopped();
    private volatile MediaState mediaState = MediaState.IDLE;
    private volatile String mediaDetail = "";

    private final Object frameLock = new Object();
    private ByteBuffer staging;
    private int frameWidth;
    private int frameHeight;
    private long frameSequence;
    private long uploadedSequence;

    private VideoTexture texture;
    private int textureWidth = -1;
    private int textureHeight = -1;
    private RenderType renderType;
    private Identifier cachedTextureId;
    private String cachedTextureSource;

    private String endedUrl = "";
    private long endedPosition;
    private volatile boolean forceSeek;

    private ResolvedMedia resolved;
    private String resolvedUrl = "";

    /** 由解码预算计算出的相机距离平方，仅由客户端线程写入。 */
    public volatile double distanceSq;

    private String reportedDurationUrl = "";
    private String reportedErrorKey = "";
    private long lastFailureAt;
    private int failureCount;

    public ClientScreen(ScreenInfo info) {
        this.info = info;
    }

    public ScreenInfo info() {
        return info;
    }

    public void setInfo(ScreenInfo info) {
        this.info = info;
    }

    public Playback playback() {
        return playback;
    }

    public void setPlayback(Playback playback) {
        Playback next = playback == null ? Playback.stopped() : playback;
        Playback previous = this.playback;
        boolean urlChanged = previous == null || !previous.url().equals(next.url());
        boolean jumped = previous != null && Math.abs(next.positionMillis() - previous.positionMillis()) > 2_000L;
        // 循环重播时 URL 与位置都不变，只有时间线重新计时；不认这一条，ended 永远清不掉。
        // 片尾暂停也会换 epoch，因此要求「未暂停且位置没有前进」，避免多开一次解码。
        boolean restarted = previous != null
                && next.startEpochMillis() != previous.startEpochMillis()
                && !next.paused()
                && next.positionMillis() < Math.max(1L, previous.positionMillis());
        this.playback = next;
        if (urlChanged) {
            this.reportedDurationUrl = "";
            this.reportedErrorKey = "";
            this.failureCount = 0;
        }
        if (urlChanged) {
            clearResolved();
        }
        if (jumped) {
            // 服务端主动跳转（seek / 循环回到开头）时立即校正，不必等 7 秒冷却
            forceSeek = true;
        }
        if (urlChanged || jumped || restarted || next.isStopped()) {
            clearEnded();
        }
    }

    /** 在客户端线程创建一块占位纹理，避免渲染阶段才发现纹理不存在。 */
    public void ensurePlaceholder(Minecraft minecraft) {
        ensureTexture(minecraft, 16, 16);
    }

    public boolean hasTexture() {
        return texture != null;
    }

    public Identifier textureId() {
        String source = info.id();
        if (cachedTextureId == null || !source.equals(cachedTextureSource)) {
            cachedTextureSource = source;
            cachedTextureId = Identifier.fromNamespaceAndPath("mikuvideo", "screen/" + safePath(source));
        }
        return cachedTextureId;
    }

    public RenderType renderType() {
        if (renderType == null) {
            try {
                renderType = RenderTypes.entityTranslucentEmissive(textureId());
            } catch (RuntimeException error) {
                return null;
            }
        }
        return renderType;
    }

    /** 把任意 id 编码成 Identifier 允许的 path，避免非法字符把渲染线程打崩。 */
    private static String safePath(String id) {
        String value = id == null ? "" : id.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(Math.min(48, value.length()));
        for (int i = 0; i < value.length() && builder.length() < 48; i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '/') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        if (builder.length() == 0) {
            builder.append("screen");
        }
        return builder.append('-').append(Integer.toHexString(value.hashCode())).toString();
    }

    public void offerFrame(ByteBuffer source, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int needed = width * height * 4;
        if (source.capacity() < needed) {
            return;
        }
        synchronized (frameLock) {
            if (staging == null || staging.capacity() < needed) {
                staging = ByteBuffer.allocateDirect(needed);
            }
            staging.clear();
            ByteBuffer sourceView = source.duplicate();
            sourceView.position(0);
            sourceView.limit(needed);
            staging.put(sourceView);
            staging.flip();
            frameWidth = width;
            frameHeight = height;
            frameSequence++;
        }
    }

    /** 客户端主线程调用：把最新帧上传到纹理。 */
    public void uploadFrame(Minecraft minecraft) {
        long sequence;
        int width;
        int height;
        synchronized (frameLock) {
            if (frameSequence == uploadedSequence) {
                return;
            }
            sequence = frameSequence;
            width = frameWidth;
            height = frameHeight;
        }
        if (width <= 0 || height <= 0) {
            return;
        }
        ensureTexture(minecraft, width, height);
        if (texture == null) {
            return;
        }
        ByteBuffer destination = texture.getPixels().getPixelBytes();
        destination.clear();
        synchronized (frameLock) {
            ByteBuffer source = staging.duplicate();
            source.position(0);
            source.limit(width * height * 4);
            destination.put(source);
        }
        texture.upload();
        uploadedSequence = sequence;
    }

    private void ensureTexture(Minecraft minecraft, int width, int height) {
        if (texture != null && textureWidth == width && textureHeight == height) {
            return;
        }
        Identifier id = textureId();
        if (texture != null) {
            minecraft.getTextureManager().release(id);
            texture = null;
            renderType = null;
        }
        VideoTexture created = null;
        try {
            created = new VideoTexture("MikuVideo screen " + info.id(), width, height);
            minecraft.getTextureManager().register(id, created);
        } catch (RuntimeException error) {
            if (created != null) {
                try {
                    created.close();
                } catch (RuntimeException ignored) {
                }
            }
            textureWidth = -1;
            textureHeight = -1;
            renderType = null;
            return;
        }
        texture = created;
        textureWidth = width;
        textureHeight = height;
        renderType = null;
    }

    public void setMediaState(MediaState state, String detail) {
        this.mediaState = state;
        this.mediaDetail = detail == null ? "" : detail;
        if (state == MediaState.ERROR) {
            lastFailureAt = System.currentTimeMillis();
            failureCount++;
        }
    }

    public MediaState mediaState() {
        return mediaState;
    }

    public String mediaDetail() {
        return mediaDetail;
    }

    public long lastFailureAt() {
        return lastFailureAt;
    }

    public int failureCount() {
        return failureCount;
    }

    public void resetFailures() {
        failureCount = 0;
    }

    public boolean isEnded(String url) {
        return url != null && !url.isBlank() && url.equals(endedUrl);
    }

    public void markEnded(String url, long positionMillis) {
        this.endedUrl = url == null ? "" : url;
        this.endedPosition = positionMillis;
        setMediaState(MediaState.PAUSED, "播放结束");
    }

    public void clearEnded() {
        this.endedUrl = "";
        this.endedPosition = 0L;
    }

    public void setResolved(String url, ResolvedMedia media) {
        this.resolved = media;
        this.resolvedUrl = url == null ? "" : url;
    }

    public ResolvedMedia resolved(String url) {
        return url != null && url.equals(resolvedUrl) ? resolved : null;
    }

    public void clearResolved() {
        this.resolved = null;
        this.resolvedUrl = "";
    }

    /** 取走一次强制校正标记。 */
    public boolean consumeForceSeek() {
        if (!forceSeek) {
            return false;
        }
        forceSeek = false;
        return true;
    }

    public long expectedPositionMillis(long serverNow) {
        Playback current = playback;
        return current == null ? 0L : current.positionAt(serverNow);
    }

    public boolean shouldReportDuration(String url) {
        return url != null && !url.isBlank() && !url.equals(reportedDurationUrl);
    }

    public void markDurationReported(String url) {
        reportedDurationUrl = url;
    }

    public boolean shouldReportError(String key) {
        return key != null && !key.equals(reportedErrorKey);
    }

    public void markErrorReported(String key) {
        reportedErrorKey = key == null ? "" : key;
    }

    public String statusText(ClientConfig config) {
        Playback current = playback;
        StringBuilder builder = new StringBuilder();
        builder.append(info.name()).append(" [").append(info.id()).append("]");
        if (current == null || current.isStopped()) {
            builder.append(" 空闲");
        } else {
            builder.append(current.paused() ? " 暂停" : " 播放中");
            long position = current.positionAt(ClockSync.serverNow());
            builder.append(" ").append(format(position));
            if (current.durationMillis() > 0L) {
                builder.append("/").append(format(current.durationMillis()));
            }
        }
        if (mediaState == MediaState.ERROR) {
            builder.append(" 解码失败");
            if (!mediaDetail.isBlank()) {
                builder.append(" (").append(mediaDetail).append(")");
            }
        } else if (mediaState == MediaState.PREPARING) {
            builder.append(" 缓冲中");
        }
        return builder.toString();
    }

    private static String format(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format("%d:%02d", seconds / 60L, seconds % 60L);
    }

    public void close(Minecraft minecraft) {
        if (texture != null) {
            minecraft.getTextureManager().release(textureId());
            texture = null;
        }
        synchronized (frameLock) {
            staging = null;
            frameWidth = 0;
            frameHeight = 0;
            frameSequence = 0L;
            uploadedSequence = 0L;
        }
        renderType = null;
    }
}