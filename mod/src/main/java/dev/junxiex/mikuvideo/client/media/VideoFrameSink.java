package dev.junxiex.mikuvideo.client.media;

import java.nio.ByteBuffer;

/**
 * 解码线程交付一帧 RGBA 像素。
 *
 * <p>缓冲区由解码器复用，回调返回后内容可能被覆盖，接收方必须立即拷贝。
 * 回调在解码线程执行，禁止直接调用渲染或上传纹理。</p>
 */
@FunctionalInterface
public interface VideoFrameSink {
    void onFrame(ByteBuffer rgba, int width, int height);
}
