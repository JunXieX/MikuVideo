package dev.junxiex.mikuvideo.client.media;

import java.util.Map;

/**
 * 一次播放请求。
 *
 * @param url                网络视频地址（http/https，可为 mp4 / m3u8 / flv 等 ffmpeg 支持的格式）
 * @param headers            额外的 HTTP 头，例如 Referer、User-Agent
 * @param startPositionMillis 起播位置
 * @param maxWidth           解码宽度上限
 * @param maxHeight          解码高度上限
 * @param fps                解码帧率上限
 * @param audioEnabled       是否播放声音
 */
public record MediaRequest(
        String url,
        Map<String, String> headers,
        long startPositionMillis,
        int maxWidth,
        int maxHeight,
        int fps,
        boolean audioEnabled
) {
    public MediaRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        startPositionMillis = Math.max(0L, startPositionMillis);
        maxWidth = Math.max(64, Math.min(3840, maxWidth));
        maxHeight = Math.max(36, Math.min(2160, maxHeight));
        fps = Math.max(1, Math.min(60, fps));
    }
}
