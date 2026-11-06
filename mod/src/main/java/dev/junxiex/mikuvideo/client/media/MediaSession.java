package dev.junxiex.mikuvideo.client.media;

/** 一块屏幕对应的解码会话。实现必须允许从任意线程调用控制方法。 */
public interface MediaSession extends AutoCloseable {
    void start(MediaRequest request, VideoFrameSink sink, MediaStateListener listener);

    void setVolume(float volume);

    void pause();

    void resume();

    void seek(long positionMillis);

    void stop();

    MediaState state();

    String lastError();

    /** 已呈现到的媒体位置；未知返回 0。默认实现供上层纠偏使用。 */
    default long positionMillis() {
        return 0L;
    }

    /** 媒体总时长；未知返回 0。 */
    default long durationMillis() {
        return 0L;
    }

    /** 是否已经播放到结尾（EOF）。默认后端不区分。 */
    default boolean isEnded() {
        return false;
    }

    @Override
    void close();
}