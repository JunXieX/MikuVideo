package dev.junxiex.mikuvideo.config;

import dev.junxiex.mikuvideo.protocol.Protocol;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 插件配置的不可变快照。
 *
 * <p>每次 reload 都重新读取并构造一个新实例，通过 volatile 一次性发布，
 * 运行期只读，避免“改到一半被别的线程看到”。所有数值在读入时夹取到合理范围，
 * 配置文件写错不会导致启动失败或运行期异常。</p>
 *
 * @author JunXieX
 */
public record Settings(Limits limits, SyncSettings sync, Media media, Bilibili bilibili,
                       StorageSettings storage, Messages messages) {

    private static final List<String> DEFAULT_SCHEMES = List.of("http", "https");
    private static final List<String> DEFAULT_HEADERS =
            List.of("Referer", "User-Agent", "Origin", "Cookie", "Authorization");

    public Settings {
        limits = limits == null ? Limits.DEFAULTS : limits;
        sync = sync == null ? SyncSettings.DEFAULTS : sync;
        media = media == null ? Media.DEFAULTS : media;
        bilibili = bilibili == null ? Bilibili.DEFAULTS : bilibili;
        storage = storage == null ? StorageSettings.DEFAULTS : storage;
        messages = messages == null ? Messages.DEFAULTS : messages;
    }

    /** 数量与尺寸限制。tiers 是可选的分级限制，按权限匹配。 */
    public record Limits(int maxScreensPerPlayer, double maxScreenWidth, double maxScreenHeight,
                         double minScreenWidth, double minScreenHeight, int maxTotalScreens,
                         List<Tier> tiers) {

        /** 默认分级：普通玩家 4 块 / 8x4.5，VIP 8 块 / 16x9 且限定比例。 */
        public static final List<Tier> DEFAULT_TIERS = List.of(
                new Tier("default", "", 4, 8.0D, 4.5D, 0.0D, List.of()),
                new Tier("vip", "mikuvideo.tier.vip", 8, 16.0D, 9.0D, 0.0D,
                        List.of("16:9", "4:3", "21:9", "1:1")));

        public static final Limits DEFAULTS = new Limits(8, 16.0D, 9.0D, 0.5D, 0.5D,
                Protocol.MAX_SCREEN_COUNT, DEFAULT_TIERS);

        public Limits {
            maxScreensPerPlayer = clamp(maxScreensPerPlayer, 1, 1000);
            maxTotalScreens = clamp(maxTotalScreens, 1, Protocol.MAX_SCREEN_COUNT);
            minScreenWidth = clamp(minScreenWidth, 0.5D, 16.0D);
            minScreenHeight = clamp(minScreenHeight, 0.5D, 16.0D);
            maxScreenWidth = clamp(maxScreenWidth, minScreenWidth, 64.0D);
            maxScreenHeight = clamp(maxScreenHeight, minScreenHeight, 64.0D);
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
        }

        public double clampWidth(double value) {
            return clamp(value, minScreenWidth, maxScreenWidth);
        }

        public double clampHeight(double value) {
            return clamp(value, minScreenHeight, maxScreenHeight);
        }

        /** 没有任何分级配置时，用全局限制构造一个默认等级。 */
        public Tier fallbackTier() {
            return new Tier("default", "", maxScreensPerPlayer, maxScreenWidth, maxScreenHeight, 0.0D, List.of());
        }

        public List<Tier> effectiveTiers() {
            return tiers.isEmpty() ? List.of(fallbackTier()) : tiers;
        }
    }

    /**
     * 屏幕等级：按权限匹配，权限为空表示所有玩家的基础等级。
     *
     * <p>数值只能收紧全局限制，不能突破 {@link Limits} 的硬上限。</p>
     */
    public record Tier(String id, String permission, int maxScreens, double maxWidth, double maxHeight,
                       double maxArea, List<String> aspects) {

        public Tier {
            id = blankTo(id, "default");
            permission = permission == null ? "" : permission.trim();
            maxScreens = clamp(maxScreens, 0, 1000);
            maxWidth = clamp(maxWidth, 0.5D, 64.0D);
            maxHeight = clamp(maxHeight, 0.5D, 64.0D);
            maxArea = maxArea <= 0.0D ? 0.0D : clamp(maxArea, 0.25D, 4096.0D);
            aspects = normalizeAspects(aspects);
        }

        public boolean allowsAspect(double width, double height) {
            if (aspects.isEmpty() || height <= 0.0D) {
                return true;
            }
            double ratio = width / height;
            for (String aspect : aspects) {
                double target = ratioOf(aspect);
                if (target > 0.0D && Math.abs(ratio - target) / target <= 0.02D) {
                    return true;
                }
            }
            return false;
        }

        public String describeAspects() {
            return aspects.isEmpty() ? "不限" : String.join(" / ", aspects);
        }
    }

    /** 同步与时间线相关参数。 */
    public record SyncSettings(double viewDistance, int rescanIntervalTicks, long rebroadcastIntervalMs,
                               long startHoldMs, long commandCooldownMs, long pingIntervalMs) {

        public static final SyncSettings DEFAULTS =
                new SyncSettings(64.0D, 10, 5000L, 3000L, 150L, 30000L);

        public SyncSettings {
            viewDistance = clamp(viewDistance, 4.0D, 512.0D);
            rescanIntervalTicks = clamp(rescanIntervalTicks, 1, 200);
            rebroadcastIntervalMs = clamp(rebroadcastIntervalMs, 1000L, 120000L);
            startHoldMs = clamp(startHoldMs, 0L, 60000L);
            commandCooldownMs = clamp(commandCooldownMs, 0L, 10000L);
            pingIntervalMs = clamp(pingIntervalMs, 5000L, 600000L);
        }

        public long rebroadcastPeriodTicks() {
            return Math.max(1L, rebroadcastIntervalMs / 50L);
        }

        public long pingPeriodTicks() {
            return Math.max(1L, pingIntervalMs / 50L);
        }

        public double viewDistanceSquared() {
            return viewDistance * viewDistance;
        }
    }

    /** 媒体地址相关配置。 */
    public record Media(boolean allowPrivateHosts, List<String> allowedSchemes, List<String> blockedHosts,
                        int maxUrlLength, int maxHeaders, List<String> allowedHeaderNames) {

        public static final Media DEFAULTS = new Media(false, DEFAULT_SCHEMES, List.of(), 1024, 8, DEFAULT_HEADERS);

        public Media {
            allowedSchemes = normalize(allowedSchemes, DEFAULT_SCHEMES, true);
            blockedHosts = normalize(blockedHosts, List.of(), true);
            allowedHeaderNames = normalize(allowedHeaderNames, DEFAULT_HEADERS, false);
            maxUrlLength = clamp(maxUrlLength, 16, Protocol.MAX_URL);
            maxHeaders = clamp(maxHeaders, 0, Protocol.MAX_HEADER_COUNT);
        }
    }

    /**
     * 服务端 B 站凭证下发。
     *
     * <p>Cookie 只保存在服务端配置里，按玩家权限与风控规则下发；
     * 客户端负责加密落盘并绑定当前服务器。</p>
     */
    public record Bilibili(String cookie, boolean deliverToClients, boolean requireOnlineMode,
                           boolean allowOfflineMode, long deliverDelayMs, long refreshCooldownMs,
                           int maxDeliveriesPerHour, long credentialTtlMillis) {

        public static final Bilibili DEFAULTS = new Bilibili("", true, true, false,
                1500L, 600000L, 6, 7L * 24L * 3600L * 1000L);

        public Bilibili {
            cookie = sanitizeCookie(cookie);
            deliverDelayMs = clamp(deliverDelayMs, 0L, 60000L);
            refreshCooldownMs = clamp(refreshCooldownMs, 5000L, 86_400_000L);
            maxDeliveriesPerHour = clamp(maxDeliveriesPerHour, 1, 60);
            credentialTtlMillis = clamp(credentialTtlMillis, 60_000L, 365L * 24L * 3600L * 1000L);
        }

        public boolean configured() {
            return !cookie.isBlank();
        }

        public long ttlHours() {
            return Math.max(1L, credentialTtlMillis / 3_600_000L);
        }
    }

    /** 存储配置。 */
    public record StorageSettings(long saveDebounceMs) {

        public static final StorageSettings DEFAULTS = new StorageSettings(1500L);

        public StorageSettings {
            saveDebounceMs = clamp(saveDebounceMs, 100L, 60000L);
        }
    }

    /** 提示文案，均支持 MiniMessage。 */
    public record Messages(String prefix, String clientDownloadUrl, String noClient, String noPermission,
                           String screenNotFound) {

        // 常量必须先于 DEFAULTS 声明：DEFAULTS 的构造会读这些常量。
        private static final String DEFAULT_PREFIX = "<gradient:#66ccff:#ff99ff>[MikuVideo]</gradient> ";
        private static final String DEFAULT_DOWNLOAD_URL = "https://github.com/JunXieX/MikuVideo/releases";
        private static final String DEFAULT_NO_CLIENT =
                "<red>未检测到客户端模组，请安装 MikuVideo 客户端模组后重新进入服务器。";
        private static final String DEFAULT_NO_PERMISSION = "<red>你没有权限执行该操作。";
        private static final String DEFAULT_SCREEN_NOT_FOUND = "<red>找不到该屏幕，请用 /mv list 查看可用屏幕。";

        public static final Messages DEFAULTS = new Messages(
                DEFAULT_PREFIX, DEFAULT_DOWNLOAD_URL, DEFAULT_NO_CLIENT,
                DEFAULT_NO_PERMISSION, DEFAULT_SCREEN_NOT_FOUND);

        public Messages {
            // 不能用 DEFAULTS：类初始化时 DEFAULTS 本身还在构造中，引用会得到 null。
            prefix = blankTo(prefix, DEFAULT_PREFIX);
            clientDownloadUrl = trimTo(clientDownloadUrl, Protocol.MAX_URL, DEFAULT_DOWNLOAD_URL);
            noClient = blankTo(noClient, DEFAULT_NO_CLIENT);
            noPermission = blankTo(noPermission, DEFAULT_NO_PERMISSION);
            screenNotFound = blankTo(screenNotFound, DEFAULT_SCREEN_NOT_FOUND);
        }
    }

    /** 全默认配置。 */
    public static Settings defaults() {
        return new Settings(Limits.DEFAULTS, SyncSettings.DEFAULTS, Media.DEFAULTS, Bilibili.DEFAULTS,
                StorageSettings.DEFAULTS, Messages.DEFAULTS);
    }

    /** 读取配置，任何异常都退回默认值并记录日志。 */
    public static Settings load(Plugin plugin) {
        if (plugin == null) {
            return defaults();
        }
        try {
            FileConfiguration config = plugin.getConfig();
            return read(config);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("读取配置失败，已回退到默认值: " + e.getMessage());
            return defaults();
        }
    }

    /** 从配置节读取，缺失项使用默认值。 */
    public static Settings read(ConfigurationSection root) {
        if (root == null) {
            return defaults();
        }
        Limits limits = new Limits(
                intOf(root, "limits.max-screens-per-player", 8, 1, 1000),
                doubleOf(root, "limits.max-screen-width", 16.0D, 0.5D, 64.0D),
                doubleOf(root, "limits.max-screen-height", 9.0D, 0.5D, 64.0D),
                doubleOf(root, "limits.min-screen-width", 0.5D, 0.5D, 16.0D),
                doubleOf(root, "limits.min-screen-height", 0.5D, 0.5D, 16.0D),
                intOf(root, "limits.max-total-screens", Protocol.MAX_SCREEN_COUNT, 1, Protocol.MAX_SCREEN_COUNT),
                readTiers(root, 8, 16.0D, 9.0D));

        SyncSettings sync = new SyncSettings(
                doubleOf(root, "sync.view-distance", 64.0D, 4.0D, 512.0D),
                intOf(root, "sync.rescan-interval-ticks", 10, 1, 200),
                longOf(root, "sync.rebroadcast-interval-ms", 5000L, 1000L, 120000L),
                longOf(root, "sync.start-hold-ms", 3000L, 0L, 60000L),
                longOf(root, "sync.command-cooldown-ms", 150L, 0L, 10000L),
                longOf(root, "sync.ping-interval-ms", 30000L, 5000L, 600000L));

        Media media = new Media(
                boolOf(root, "media.allow-private-hosts", false),
                listOf(root, "media.allowed-schemes", DEFAULT_SCHEMES),
                listOf(root, "media.blocked-hosts", List.of()),
                intOf(root, "media.max-url-length", 1024, 16, Protocol.MAX_URL),
                intOf(root, "media.max-headers", 8, 0, Protocol.MAX_HEADER_COUNT),
                listOf(root, "media.allowed-header-names", DEFAULT_HEADERS));

        Bilibili bilibili = new Bilibili(
                root.getString("bilibili.cookie", ""),
                boolOf(root, "bilibili.deliver-to-clients", true),
                boolOf(root, "bilibili.require-online-mode", true),
                boolOf(root, "bilibili.allow-offline-mode", false),
                longOf(root, "bilibili.deliver-delay-ms", 1500L, 0L, 60000L),
                longOf(root, "bilibili.refresh-cooldown-ms", 600000L, 5000L, 86400000L),
                intOf(root, "bilibili.max-deliveries-per-hour", 6, 1, 60),
                longOf(root, "bilibili.credential-ttl-hours", 168L, 1L, 8760L) * 3600000L);

        StorageSettings storage = new StorageSettings(
                longOf(root, "storage.save-debounce-ms", 1500L, 100L, 60000L));

        Messages messages = new Messages(
                root.getString("messages.prefix"),
                root.getString("messages.client-download-url"),
                root.getString("messages.no-client"),
                root.getString("messages.no-permission"),
                root.getString("messages.screen-not-found"));

        return new Settings(limits, sync, media, bilibili, storage, messages);
    }

    private static List<Tier> readTiers(ConfigurationSection root, int defaultScreens,
                                        double defaultWidth, double defaultHeight) {
        ConfigurationSection section = root.getConfigurationSection("limits.tiers");
        if (section == null) {
            // 旧配置没有 tiers：保持全局限制，升级后行为不变
            return List.of();
        }
        List<Tier> tiers = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection tier = section.getConfigurationSection(key);
            if (tier == null) {
                continue;
            }
            List<String> aspects = tier.getStringList("aspects");
            if (aspects.isEmpty()) {
                String single = tier.getString("aspects");
                if (single != null && !single.isBlank()) {
                    aspects = List.of(single);
                }
            }
            tiers.add(new Tier(key, tier.getString("permission", ""),
                    tier.getInt("max-screens", defaultScreens),
                    tier.getDouble("max-width", defaultWidth),
                    tier.getDouble("max-height", defaultHeight),
                    tier.getDouble("max-area", 0.0D),
                    aspects));
        }
        tiers.sort(Comparator.comparingInt(Tier::maxScreens));
        return List.copyOf(tiers);
    }

    private static List<String> normalizeAspects(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim().replace('/', ':');
            if (ratioOf(trimmed) > 0.0D) {
                unique.add(trimmed);
            }
        }
        return List.copyOf(unique);
    }

    private static double ratioOf(String aspect) {
        int colon = aspect.indexOf(':');
        if (colon <= 0) {
            return -1.0D;
        }
        try {
            double width = Double.parseDouble(aspect.substring(0, colon).trim());
            double height = Double.parseDouble(aspect.substring(colon + 1).trim());
            if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0.0D || height <= 0.0D) {
                return -1.0D;
            }
            return width / height;
        } catch (NumberFormatException error) {
            return -1.0D;
        }
    }

    private static String sanitizeCookie(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\r", "").replace("\n", "").trim();
        if (cleaned.length() > Protocol.MAX_COOKIE) {
            cleaned = cleaned.substring(0, Protocol.MAX_COOKIE);
        }
        return cleaned;
    }

    private static int intOf(ConfigurationSection section, String path, int def, int min, int max) {
        Object raw = section.get(path);
        if (raw instanceof Number number) {
            return clamp(number.intValue(), min, max);
        }
        if (raw instanceof String text) {
            try {
                return clamp(Integer.parseInt(text.trim()), min, max);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static long longOf(ConfigurationSection section, String path, long def, long min, long max) {
        Object raw = section.get(path);
        if (raw instanceof Number number) {
            return clamp(number.longValue(), min, max);
        }
        if (raw instanceof String text) {
            try {
                return clamp(Long.parseLong(text.trim()), min, max);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static double doubleOf(ConfigurationSection section, String path, double def, double min, double max) {
        Object raw = section.get(path);
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return Double.isFinite(value) ? clamp(value, min, max) : def;
        }
        if (raw instanceof String text) {
            try {
                double value = Double.parseDouble(text.trim());
                return Double.isFinite(value) ? clamp(value, min, max) : def;
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static boolean boolOf(ConfigurationSection section, String path, boolean def) {
        Object raw = section.get(path);
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.equalsIgnoreCase("true")) {
                return true;
            }
            if (trimmed.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return def;
    }

    private static List<String> listOf(ConfigurationSection section, String path, List<String> def) {
        List<?> raw = section.getList(path);
        if (raw == null) {
            String single = section.getString(path);
            if (single == null || single.isBlank()) {
                return def;
            }
            return List.of(single.trim());
        }
        List<String> values = new ArrayList<>(raw.size());
        for (Object entry : raw) {
            if (entry != null) {
                values.add(String.valueOf(entry));
            }
        }
        return values.isEmpty() ? def : List.copyOf(values);
    }

    private static List<String> normalize(List<String> values, List<String> def, boolean lowerCase) {
        if (values == null || values.isEmpty()) {
            return List.copyOf(def);
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            unique.add(lowerCase ? trimmed.toLowerCase(Locale.ROOT) : trimmed);
        }
        if (unique.isEmpty()) {
            return List.copyOf(def);
        }
        return List.copyOf(unique);
    }

    private static String blankTo(String value, String def) {
        if (value == null || value.isBlank()) {
            return def;
        }
        return value;
    }

    private static String trimTo(String value, int max, String def) {
        if (value == null || value.isBlank()) {
            return def;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}