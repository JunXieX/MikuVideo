package dev.junxiex.mikuvideo.client.media;

/** 解码状态变化回调，可能在解码线程触发。 */
@FunctionalInterface
public interface MediaStateListener {
    void onStateChanged(MediaState state, String detail);
}
