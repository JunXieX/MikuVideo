package dev.junxiex.mikuvideo.screen;

import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.protocol.ScreenInfo;
import dev.junxiex.mikuvideo.util.Text;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Objects;
import java.util.UUID;

/**
 * 一块屏幕的完整定义。不可变，修改一律生成新实例。
 *
 * <p>几何用「中心点 + 法线 / 右 / 上」描述，与协议里的 {@link ScreenInfo} 一一对应，
 * 客户端拿到后直接构造四边形，不需要自己推导朝向。</p>
 *
 * @author JunXieX
 */
public record Screen(
        String id,
        String name,
        UUID owner,
        String world,
        double cx,
        double cy,
        double cz,
        double nx,
        double ny,
        double nz,
        double rx,
        double ry,
        double rz,
        double ux,
        double uy,
        double uz,
        float width,
        float height,
        int volume,
        boolean publicScreen,
        boolean loop,
        long createdAt
) {

    /** 屏幕最小边长，避免客户端把四边形退化成一条线。 */
    public static final float MIN_SIDE = 0.5F;
    /** 屏幕最大边长，实际可用范围由配置进一步收紧。 */
    public static final float MAX_SIDE = 64.0F;

    public Screen {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(world, "world");
        // 协议按 UTF-8 字节数校验字符串，这里统一兜底，避免广播时编码失败
        name = Text.trimBytes(name, Protocol.MAX_SCREEN_NAME);
        world = Text.trimBytes(world, Protocol.MAX_WORLD_KEY);
        width = Math.max(MIN_SIDE, Math.min(MAX_SIDE, width));
        height = Math.max(MIN_SIDE, Math.min(MAX_SIDE, height));
        volume = Math.max(0, Math.min(100, volume));
    }

    /** 由几何命中结果创建屏幕，默认公开。 */
    public static Screen create(String id, String name, UUID owner, ScreenGeometry.Hit hit,
                                float width, float height, int volume, long now) {
        Basis basis = hit.basis();
        return new Screen(id, name, owner, hit.world(),
                hit.cx(), hit.cy(), hit.cz(),
                basis.normal().getX(), basis.normal().getY(), basis.normal().getZ(),
                basis.right().getX(), basis.right().getY(), basis.right().getZ(),
                basis.up().getX(), basis.up().getY(), basis.up().getZ(),
                width, height, volume, true, false, now);
    }

    public Vector center() {
        return new Vector(cx, cy, cz);
    }

    public Vector normal() {
        return new Vector(nx, ny, nz);
    }

    public Vector right() {
        return new Vector(rx, ry, rz);
    }

    public Vector up() {
        return new Vector(ux, uy, uz);
    }

    public Basis basis() {
        return new Basis(normal(), right(), up());
    }

    public boolean ownedBy(UUID playerId) {
        return owner.equals(playerId);
    }

    /** 到某个坐标的距离平方，用于可见性判断。 */
    public double distanceSquared(double x, double y, double z) {
        double dx = cx - x;
        double dy = cy - y;
        double dz = cz - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 到某个位置的距离平方。 */
    public double distanceSquared(Location location) {
        if (location == null) {
            return Double.MAX_VALUE;
        }
        return distanceSquared(location.getX(), location.getY(), location.getZ());
    }

    public Screen withName(String newName) {
        return new Screen(id, newName, owner, world, cx, cy, cz, nx, ny, nz, rx, ry, rz, ux, uy, uz,
                width, height, volume, publicScreen, loop, createdAt);
    }

    public Screen withSize(float newWidth, float newHeight) {
        return new Screen(id, name, owner, world, cx, cy, cz, nx, ny, nz, rx, ry, rz, ux, uy, uz,
                newWidth, newHeight, volume, publicScreen, loop, createdAt);
    }

    public Screen withVolume(int newVolume) {
        return new Screen(id, name, owner, world, cx, cy, cz, nx, ny, nz, rx, ry, rz, ux, uy, uz,
                width, height, newVolume, publicScreen, loop, createdAt);
    }

    public Screen withPublic(boolean visible) {
        return new Screen(id, name, owner, world, cx, cy, cz, nx, ny, nz, rx, ry, rz, ux, uy, uz,
                width, height, volume, visible, loop, createdAt);
    }

    /** 移动到新位置 / 新朝向。 */
    public Screen withGeometry(ScreenGeometry.Hit hit) {
        Basis basis = hit.basis();
        return new Screen(id, name, owner, hit.world(),
                hit.cx(), hit.cy(), hit.cz(),
                basis.normal().getX(), basis.normal().getY(), basis.normal().getZ(),
                basis.right().getX(), basis.right().getY(), basis.right().getZ(),
                basis.up().getX(), basis.up().getY(), basis.up().getZ(),
                width, height, volume, publicScreen, loop, createdAt);
    }

    /** 循环播放：播到结尾后从头重新计时。 */
    public Screen withLoop(boolean enabled) {
        return new Screen(id, name, owner, world, cx, cy, cz, nx, ny, nz, rx, ry, rz, ux, uy, uz,
                width, height, volume, publicScreen, enabled, createdAt);
    }

    /** 转成协议对象，供同步使用。 */
    public ScreenInfo toInfo() {
        return new ScreenInfo(id, name, owner, world, cx, cy, cz,
                (float) nx, (float) ny, (float) nz,
                (float) rx, (float) ry, (float) rz,
                (float) ux, (float) uy, (float) uz,
                width, height, volume, publicScreen, createdAt);
    }

    /** 屏幕的三轴基向量，保证右手系且已归一化。 */
    public record Basis(Vector normal, Vector right, Vector up) {
        public Basis {
            normal = unit(normal, new Vector(0, 0, 1));
            right = unit(right, new Vector(1, 0, 0));
            up = unit(up, new Vector(0, 1, 0));
        }

        @Override
        public Vector normal() {
            return normal.clone();
        }

        @Override
        public Vector right() {
            return right.clone();
        }

        @Override
        public Vector up() {
            return up.clone();
        }

        private static Vector unit(Vector value, Vector fallback) {
            if (value == null) {
                return fallback.clone();
            }
            Vector copy = value.clone();
            if (!Double.isFinite(copy.getX()) || !Double.isFinite(copy.getY()) || !Double.isFinite(copy.getZ())
                    || copy.lengthSquared() < 1.0E-9D) {
                return fallback.clone();
            }
            return copy.normalize();
        }
    }
}
