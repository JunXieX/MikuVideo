package dev.junxiex.mikuvideo.client.media;

import java.util.Map;

/**
 * 一次播放请求。
 *
 * <p>DASH 源（B 站等）的视频与音频是两个独立地址，分别带各自的请求头；
 * 普通直链播放时 {@code audioUrl} 留空，音频沿用视频地址。</p>
 *
 * @param url                 视频地址（http/https，可为 mp4 / m3u8 / flv 等 ffmpeg 支持的格式）
 * @param audioUrl            音频地址；空表示与视频同源
 * @param headers             视频请求头，例如 Referer、User-Agent
 * @param audioHeaders        音频请求头；空表示沿用视频请求头
 * @param startPositionMillis 起播位置
 * @param maxWidth            解码宽度上限
 * @param maxHeight           解码高度上限
 * @param fps                 解码帧率上限
 * @param audioEnabled        是否播放声音
 * @param live                是否是直播流（直播不探测片长、不 seek）
 */
public record MediaRequest(
        String url,
        String audioUrl,
        Map<String, String> headers,
        Map<String, String> audioHeaders,
        long startPositionMillis,
        int maxWidth,
        int maxHeight,
        int fps,
        boolean audioEnabled,
        boolean live
) {
    public MediaRequest {
        url = url == null ? "" : url;
        audioUrl = audioUrl == null ? "" : audioUrl;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        audioHeaders = audioHeaders == null || audioHeaders.isEmpty() ? headers : Map.copyOf(audioHeaders);
        startPositionMillis = Math.max(0L, startPositionMillis);
        maxWidth = Math.max(64, Math.min(3840, maxWidth));
        maxHeight = Math.max(36, Math.min(2160, maxHeight));
        fps = Math.max(1, Math.min(60, fps));
    }

    /** 兼容普通直链的构造方式：音频与视频同源、同请求头。 */
    public MediaRequest(String url, Map<String, String> headers, long startPositionMillis,
                        int maxWidth, int maxHeight, int fps, boolean audioEnabled) {
        this(url, "", headers, Map.of(), startPositionMillis, maxWidth, maxHeight, fps, audioEnabled, false);
    }

    public String effectiveAudioUrl() {
        return audioUrl.isBlank() ? url : audioUrl;
    }
}