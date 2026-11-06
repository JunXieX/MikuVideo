package dev.junxiex.mikuvideo.client.media;

/** 解码后端工厂。默认实现是外部 ffmpeg 进程。 */
public interface MediaEngine {
    MediaSession createSession();

    boolean isAvailable();

    /** 供设置界面/提示使用的可读状态。 */
    String statusText();

    /** 指定 ffmpeg 路径；不支持的实现可忽略。 */
    default void setPreferredPath(String path) {
    }

    /** 是否允许在缺失时自动下载；不支持的实现可忽略。 */
    default void setAutoDownload(boolean enabled) {
    }
}