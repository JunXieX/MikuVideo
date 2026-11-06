package dev.junxiex.mikuvideo.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.nio.ByteBuffer;

/**
 * 视频帧纹理。DynamicTexture 默认 NEAREST/REPEAT，这里改成 CLAMP + 双线性，
 * 屏幕放大时不会出现边缘采样错误。
 */
public final class VideoTexture extends DynamicTexture {
    public VideoTexture(String label, int width, int height) {
        super(label, width, height, true);
        this.sampler = RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        ByteBuffer pixels = getPixels().getPixelBytes();
        pixels.clear();
        int count = width * height;
        for (int i = 0; i < count; i++) {
            pixels.put((byte) 0x16).put((byte) 0x1A).put((byte) 0x20).put((byte) 0xFF);
        }
        pixels.flip();
        upload();
    }
}
