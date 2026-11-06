package dev.junxiex.mikuvideo.client.screen;

import dev.junxiex.mikuvideo.client.config.ClientConfig;
import dev.junxiex.mikuvideo.client.media.MediaState;
import dev.junxiex.mikuvideo.client.net.ClockSync;
import dev.junxiex.mikuvideo.client.render.VideoTexture;
import dev.junxiex.mikuvideo.protocol.Playback;
import dev.junxiex.mikuvideo.protocol.ScreenInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;

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

    /** 由解码预算计算出的相机距离平方，仅由客户端线程写入。 */
    public volatile double distanceSq;

    private String reportedDurationUrl = "";
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
        this.playback = next;
        if (urlChanged) {
            this.reportedDurationUrl = "";
            this.failureCount = 0;
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
        return Identifier.fromNamespaceAndPath("mikuvideo", "screen/" + info.id());
    }

    public RenderType renderType() {
        if (renderType == null) {
            renderType = RenderTypes.entityTranslucentEmissive(textureId());
        }
        return renderType;
    }

    public void offerFrame(ByteBuffer source, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int needed = width * height * 4;
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
        if (texture != null) {
            minecraft.getTextureManager().release(textureId());
        }
        texture = new VideoTexture("MikuVideo screen " + info.id(), width, height);
        minecraft.getTextureManager().register(textureId(), texture);
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
    }
}