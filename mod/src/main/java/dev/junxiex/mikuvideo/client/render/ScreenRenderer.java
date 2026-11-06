package dev.junxiex.mikuvideo.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.junxiex.mikuvideo.client.MikuVideoClient;
import dev.junxiex.mikuvideo.client.config.ClientConfig;
import dev.junxiex.mikuvideo.client.screen.ClientScreen;
import dev.junxiex.mikuvideo.client.screen.ScreenRegistry;
import dev.junxiex.mikuvideo.protocol.ScreenInfo;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/** 在世界渲染阶段把每块屏幕作为贴图四边形提交。 */
public final class ScreenRenderer {
    private ScreenRenderer() {
    }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(ScreenRenderer::collect);
    }

    private static void collect(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        PoseStack poseStack = context.poseStack();
        if (poseStack == null) {
            return;
        }
        CameraRenderState camera = context.levelState().cameraRenderState;
        String dimension = minecraft.level.dimension().identifier().toString();
        ClientConfig config = MikuVideoClient.config();
        double maxDistance = config.viewDistance;
        double maxDistanceSq = maxDistance * maxDistance;
        Vec3 cameraPos = camera.pos;
        int brightness = (int) Math.round(255.0 * (config.brightness / 100.0));
        int color = 0xFF000000 | (brightness << 16) | (brightness << 8) | brightness;

        for (ClientScreen screen : ScreenRegistry.INSTANCE.all()) {
            ScreenInfo info = screen.info();
            if (!info.world().equals(dimension) || info.width() <= 0.0F || info.height() <= 0.0F) {
                continue;
            }
            double dx = info.x() - cameraPos.x;
            double dy = info.y() - cameraPos.y;
            double dz = info.z() - cameraPos.z;
            if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
                continue;
            }
            if (!screen.hasTexture()) {
                continue;
            }
            RenderType renderType = screen.renderType();
            if (renderType == null) {
                continue;
            }
            float halfWidth = info.width() / 2.0F;
            float halfHeight = info.height() / 2.0F;
            float rightX = info.rx();
            float rightY = info.ry();
            float rightZ = info.rz();
            float upX = info.ux();
            float upY = info.uy();
            float upZ = info.uz();
            float normalX = info.nx();
            float normalY = info.ny();
            float normalZ = info.nz();

            poseStack.pushPose();
            poseStack.translate(dx, dy, dz);
            context.submitNodeCollector().submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
                vertex(buffer, pose, rightX, rightY, rightZ, upX, upY, upZ, halfWidth, halfHeight, 0.0F, 1.0F, 0.0F, 1.0F, color, normalX, normalY, normalZ);
                vertex(buffer, pose, rightX, rightY, rightZ, upX, upY, upZ, halfWidth, halfHeight, 1.0F, 1.0F, 1.0F, 1.0F, color, normalX, normalY, normalZ);
                vertex(buffer, pose, rightX, rightY, rightZ, upX, upY, upZ, halfWidth, halfHeight, 1.0F, 0.0F, 1.0F, 0.0F, color, normalX, normalY, normalZ);
                vertex(buffer, pose, rightX, rightY, rightZ, upX, upY, upZ, halfWidth, halfHeight, 0.0F, 0.0F, 0.0F, 0.0F, color, normalX, normalY, normalZ);
            });
            poseStack.popPose();
        }
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose,
                               float rightX, float rightY, float rightZ,
                               float upX, float upY, float upZ,
                               float halfWidth, float halfHeight,
                               float u, float v, float texU, float texV,
                               int color, float normalX, float normalY, float normalZ) {
        float localX = rightX * (u - 0.5F) * halfWidth * 2.0F + upX * (0.5F - v) * halfHeight * 2.0F;
        float localY = rightY * (u - 0.5F) * halfWidth * 2.0F + upY * (0.5F - v) * halfHeight * 2.0F;
        float localZ = rightZ * (u - 0.5F) * halfWidth * 2.0F + upZ * (0.5F - v) * halfHeight * 2.0F;
        buffer.addVertex(pose, localX, localY, localZ)
                .setColor(color)
                .setUv(texU, texV)
                .setLight(0xF000F0)
                .setOverlay(0)
                .setNormal(pose, normalX, normalY, normalZ);
    }
}