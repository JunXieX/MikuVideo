package dev.junxiex.mikuvideo.client.media.resolve;

import dev.junxiex.mikuvideo.client.media.MediaRequest;

import java.util.Map;

/** 解析后的可播放媒体；DASH 源会带独立的音频地址与请求头。 */
public record ResolvedMedia(
        String videoUrl,
        String audioUrl,
        Map<String, String> videoHeaders,
        Map<String, String> audioHeaders,
        long durationMillis,
        String title,
        boolean live
) {
    public ResolvedMedia {
        videoUrl = videoUrl == null ? "" : videoUrl;
        audioUrl = audioUrl == null ? "" : audioUrl;
        videoHeaders = videoHeaders == null ? Map.of() : Map.copyOf(videoHeaders);
        audioHeaders = audioHeaders == null || audioHeaders.isEmpty() ? videoHeaders : Map.copyOf(audioHeaders);
        durationMillis = Math.max(0L, durationMillis);
        title = title == null ? "" : title;
    }

    public MediaRequest toRequest(long startPositionMillis, int width, int height, int fps, boolean audioEnabled,
                                  Map<String, String> extraHeaders) {
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>();
        if (extraHeaders != null) {
            merged.putAll(extraHeaders);
        }
        merged.putAll(videoHeaders);
        return new MediaRequest(videoUrl, audioUrl, merged, audioHeaders,
                startPositionMillis, width, height, fps, audioEnabled, live);
    }
}