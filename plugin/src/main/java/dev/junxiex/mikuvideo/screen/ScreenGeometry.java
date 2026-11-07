package dev.junxiex.mikuvideo.screen;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Optional;

/**
 * 由方块面推导屏幕几何。
 *
 * <p>水平四面用 {@code up × normal} 得到右向量，保证玩家正对面看时画面不镜像；
 * UP / DOWN 两面单独给稳定基向量，避免叉积退化。</p>
 *
 * @author JunXieX
 */
public final class ScreenGeometry {

    /** 视线最远检测距离（格）。 */
    public static final double REACH = 6.0D;
    /** 屏幕中心相对方块表面的外偏移，避免与方块面共面导致 z-fighting。 */
    public static final double SURFACE_OFFSET = 0.02D;

    private static final Vector WORLD_UP = new Vector(0, 1, 0);

    private ScreenGeometry() {
    }

    /** 一次射线命中得到的屏幕几何。 */
    public record Hit(String world, double cx, double cy, double cz, Screen.Basis basis) {
    }

    /** 取玩家视线 6 格内的方块面，必须在玩家所属区域线程调用。 */
    public static Optional<Hit> look(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-6D) {
            return Optional.empty();
        }
        RayTraceResult result = world.rayTraceBlocks(eye, direction.normalize(), REACH, FluidCollisionMode.NEVER, true);
        if (result == null || result.getHitBlock() == null || result.getHitBlockFace() == null) {
            return Optional.empty();
        }
        BlockFace face = result.getHitBlockFace();
        Optional<Screen.Basis> basis = basisFor(face);
        if (basis.isEmpty()) {
            return Optional.empty();
        }
        Block block = result.getHitBlock();
        Vector center = new Vector(block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D)
                .add(face.getDirection().multiply(0.5D))
                .add(basis.get().normal().multiply(SURFACE_OFFSET));
        return Optional.of(new Hit(world.getKey().toString(), center.getX(), center.getY(), center.getZ(), basis.get()));
    }

    /** 方块面到屏幕基向量；斜面返回空。 */
    public static Optional<Screen.Basis> basisFor(BlockFace face) {
        if (face == null || !face.isCartesian() || face == BlockFace.SELF) {
            return Optional.empty();
        }
        Vector normal = face.getDirection();
        if (normal.lengthSquared() < 1.0E-9D) {
            return Optional.empty();
        }
        normal = normal.normalize();
        return switch (face) {
            // 顶/底面固定 up 的符号要与 right × up = normal 保持一致，否则客户端按固定绕序绘制时被背面剔除
            case UP -> Optional.of(new Screen.Basis(normal, new Vector(1, 0, 0), new Vector(0, 0, -1)));
            case DOWN -> Optional.of(new Screen.Basis(normal, new Vector(1, 0, 0), new Vector(0, 0, 1)));
            default -> {
                Vector up = WORLD_UP.clone();
                Vector right = up.clone().crossProduct(normal);
                if (right.lengthSquared() < 1.0E-9D) {
                    yield Optional.empty();
                }
                yield Optional.of(new Screen.Basis(normal, right.normalize(), up));
            }
        };
    }
}