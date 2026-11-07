package dev.junxiex.mikuvideo.limit;

import dev.junxiex.mikuvideo.config.Settings;
import dev.junxiex.mikuvideo.permission.Permissions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Supplier;

/**
 * 玩家屏幕等级与尺寸校验。
 *
 * <p>配置里的 tiers 按权限匹配，取「匹配到的最高等级」；等级只能收紧全局上限。
 * {@code mikuvideo.limit.unlimited} 直接获得全局上限且不受数量限制。</p>
 *
 * @author JunXieX
 */
public final class LimitService {
    private final Supplier<Settings> settings;

    public LimitService(Supplier<Settings> settings) {
        this.settings = settings == null ? Settings::defaults : settings;
    }

    /** 控制台或非玩家发送者视为无限制。 */
    public Settings.Tier tierFor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return unlimitedTier();
        }
        return tierFor(player);
    }

    private Settings.Tier unlimitedTier() {
        Settings.Limits limits = settings.get().limits();
        return new Settings.Tier("unlimited", "", 1000,
                limits.maxScreenWidth(), limits.maxScreenHeight(), 0.0D, List.of());
    }

    /** 当前玩家适用的等级。 */
    public Settings.Tier tierFor(Player player) {
        Settings.Limits limits = settings.get().limits();
        if (player != null && player.hasPermission(Permissions.LIMIT_UNLIMITED)) {
            return unlimitedTier();
        }
        Settings.Tier best = null;
        for (Settings.Tier tier : limits.effectiveTiers()) {
            if (!tier.permission().isBlank() && (player == null || !player.hasPermission(tier.permission()))) {
                continue;
            }
            if (best == null || tier.maxScreens() >= best.maxScreens()) {
                best = tier;
            }
        }
        if (best != null) {
            return best;
        }
        // 配置了分级但玩家一个都不匹配：按 0 块处理，避免绕过权限白拿额度
        return new Settings.Tier("none", "", 0, limits.minScreenWidth(), limits.minScreenHeight(), 0.0D, List.of());
    }

    /**
     * 校验尺寸与数量。
     *
     * @param owned 已拥有的屏幕数；传负数表示只校验尺寸
     * @return 错误文本；通过时返回 {@code null}
     */
    public String validate(CommandSender sender, double width, double height, int owned) {
        Settings.Limits limits = settings.get().limits();
        Settings.Tier tier = tierFor(sender);
        if (owned >= 0 && owned >= tier.maxScreens()) {
            return "屏幕数量已达上限（" + tier.maxScreens() + " 块，等级 " + tier.id() + "）";
        }
        double maxWidth = Math.min(limits.maxScreenWidth(), tier.maxWidth());
        double maxHeight = Math.min(limits.maxScreenHeight(), tier.maxHeight());
        if (width > maxWidth + 1.0E-4D) {
            return "宽度超过上限 " + format(maxWidth) + "（等级 " + tier.id() + "）";
        }
        if (height > maxHeight + 1.0E-4D) {
            return "高度超过上限 " + format(maxHeight) + "（等级 " + tier.id() + "）";
        }
        if (tier.maxArea() > 0.0D && width * height > tier.maxArea() + 1.0E-4D) {
            return "面积超过上限 " + format(tier.maxArea()) + "（等级 " + tier.id() + "）";
        }
        if (!tier.allowsAspect(width, height)) {
            return "该等级只允许比例 " + tier.describeAspects() + "（等级 " + tier.id() + "）";
        }
        return null;
    }

    /** 玩家当前等级的可读描述，用于创建成功提示。 */
    public String describe(Player player) {
        Settings.Tier tier = tierFor(player);
        return tier.id() + "（最多 " + tier.maxScreens() + " 块，最大 "
                + format(tier.maxWidth()) + " x " + format(tier.maxHeight())
                + "，比例 " + tier.describeAspects() + "）";
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format("%.2f", value);
    }
}