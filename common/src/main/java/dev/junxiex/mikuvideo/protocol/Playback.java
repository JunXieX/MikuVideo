package dev.junxiex.mikuvideo.protocol;

import java.util.Map;
import java.util.Objects;

/**
 * 一块屏幕的播放状态快照。
 *
 * <p>{@code startEpochMillis} 是服务端发出该状态时的系统时间（UTC 毫秒）。
 * 客户端按 {@code positionMillis + (now - startEpochMillis) * rate} 推算当前进度，
 * 因此服务端不需要逐帧发包。</p>
 */
public record Playback(
        String url,
        Map<String, String> headers,
        long positionMillis,
        long startEpochMillis,
        boolean paused,
        float rate,
        int volume,
        boolean audioEnabled,
        long durationMillis
) {
    public Playback {
        url = url == null ? "" : url;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        positionMillis = Math.max(0L, positionMillis);
        rate = Float.isFinite(rate) ? Math.max(0.25f, Math.min(4.0f, rate)) : 1.0f;
        volume = Math.max(0, Math.min(100, volume));
        durationMillis = Math.max(0L, durationMillis);
    }

    public static Playback stopped() {
        return new Playback("", Map.of(), 0L, 0L, true, 1.0f, 100, false, 0L);
    }

    public boolean isStopped() {
        return url.isBlank();
    }

    public Playback withDuration(long newDurationMillis) {
        return new Playback(url, headers, positionMillis, startEpochMillis, paused, rate, volume, audioEnabled, newDurationMillis);
    }

    public long positionAt(long serverEpochMillis) {
        if (paused || startEpochMillis <= 0L) {
            return Math.max(0L, positionMillis);
        }
        long delta = Math.max(0L, serverEpochMillis - startEpochMillis);
        return Math.max(0L, positionMillis + (long) (delta * (double) rate));
    }

    public Playback withVolume(int newVolume) {
        return new Playback(url, headers, positionMillis, startEpochMillis, paused, rate, newVolume, audioEnabled, durationMillis);
    }
}