package dev.junxiex.mikuvideo.protocol;

import java.util.Objects;
import java.util.UUID;

/**
 * 一块屏幕的完整几何与归属信息。
 *
 * <p>坐标是屏幕中心点；法线 / 右向量 / 上向量用于在客户端直接构造四边形，
 * 避免两端各自推导朝向导致画面镜像或反向。</p>
 */
public record ScreenInfo(
        String id,
        String name,
        UUID owner,
        String world,
        double x,
        double y,
        double z,
        float nx,
        float ny,
        float nz,
        float rx,
        float ry,
        float rz,
        float ux,
        float uy,
        float uz,
        float width,
        float height,
        int volume,
        boolean publicScreen,
        long createdAt
) {
    public ScreenInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(world, "world");
        volume = Math.max(0, Math.min(100, volume));
    }

    public double centerX() {
        return x;
    }

    public double centerY() {
        return y;
    }

    public double centerZ() {
        return z;
    }
}
