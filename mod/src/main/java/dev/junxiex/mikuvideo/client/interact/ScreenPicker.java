package dev.junxiex.mikuvideo.client.interact;

import dev.junxiex.mikuvideo.client.screen.ClientScreen;
import dev.junxiex.mikuvideo.client.screen.ScreenRegistry;
import dev.junxiex.mikuvideo.protocol.ScreenInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** 视线与屏幕四边形的射线求交。 */
public final class ScreenPicker {
    private ScreenPicker() {
    }

    public record Hit(ClientScreen screen, double distance, double u, double v) {
    }

    public static Hit pick(Minecraft minecraft, double maxDistance) {
        if (minecraft.player == null || minecraft.level == null) {
            return null;
        }
        Vec3 eye = minecraft.player.getEyePosition(1.0F);
        Vec3 direction = minecraft.player.getViewVector(1.0F);
        String dimension = minecraft.level.dimension().identifier().toString();
        Hit best = null;
        for (ClientScreen screen : ScreenRegistry.INSTANCE.all()) {
            ScreenInfo info = screen.info();
            if (!info.world().equals(dimension) || info.width() <= 0.0F || info.height() <= 0.0F) {
                continue;
            }
            Vec3 normal = new Vec3(info.nx(), info.ny(), info.nz());
            double denominator = direction.dot(normal);
            if (Math.abs(denominator) < 1.0E-5) {
                continue;
            }
            Vec3 toCenter = new Vec3(info.x() - eye.x, info.y() - eye.y, info.z() - eye.z);
            double distance = toCenter.dot(normal) / denominator;
            if (distance <= 0.0 || distance > maxDistance) {
                continue;
            }
            Vec3 point = eye.add(direction.scale(distance));
            double ox = point.x - info.x();
            double oy = point.y - info.y();
            double oz = point.z - info.z();
            double u = (ox * info.rx() + oy * info.ry() + oz * info.rz()) / info.width() + 0.5;
            double v = 0.5 - (ox * info.ux() + oy * info.uy() + oz * info.uz()) / info.height();
            if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) {
                continue;
            }
            if (best == null || distance < best.distance()) {
                best = new Hit(screen, distance, u, v);
            }
        }
        return best;
    }
}