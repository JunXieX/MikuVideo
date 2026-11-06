package dev.junxiex.mikuvideo.config;

import dev.junxiex.mikuvideo.protocol.Protocol;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
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
public record Settings(Limits limits, SyncSettings sync, Media media, StorageSettings storage, Messages messages) {

    private static final List<String> DEFAULT_SCHEMES = List.of("http", "https");
    private static final List<String> DEFAULT_HEADERS =
            List.of("Referer", "User-Agent", "Origin", "Cookie", "Authorization");

    public Settings {
        limits = limits == null ? Limits.DEFAULTS : limits;
        sync = sync == null ? SyncSettings.DEFAULTS : sync;
        media = media == null ? Media.DEFAULTS : media;
        storage = storage == null ? StorageSettings.DEFAULTS : storage;
        messages = messages == null ? Messages.DEFAULTS : messages;
    }

    /** 数量与尺寸限制。 */
    public record Limits(int maxScreensPerPlayer, double maxScreenWidth, double maxScreenHeight,
                         double minScreenWidth, double minScreenHeight, int maxTotalScreens) {

        public static final Limits DEFAULTS = new Limits(8, 16.0D, 9.0D, 0.5D, 0.5D, Protocol.MAX_SCREEN_COUNT);

        public Limits {
            maxScreensPerPlayer = clamp(maxScreensPerPlayer, 1, 1000);
            maxTotalScreens = clamp(maxTotalScreens, 1, Protocol.MAX_SCREEN_COUNT);
            minScreenWidth = clamp(minScreenWidth, 0.5D, 16.0D);
            minScreenHeight = clamp(minScreenHeight, 0.5D, 16.0D);
            maxScreenWidth = clamp(maxScreenWidth, minScreenWidth, 64.0D);
            maxScreenHeight = clamp(maxScreenHeight, minScreenHeight, 64.0D);
        }

        public double clampWidth(double value) {
            return clamp(value, minScreenWidth, maxScreenWidth);
        }

        public double clampHeight(double value) {
            return clamp(value, minScreenHeight, maxScreenHeight);
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

        public static final Messages DEFAULTS = new Messages(
                "<gradient:#66ccff:#ff99ff>[MikuVideo]</gradient> ",
                "https://example.com/mikuvideo",
                "<red>未检测到客户端模组，请安装 MikuVideo 客户端模组后重新进入服务器。",
                "<red>你没有权限执行该操作。",
                "<red>找不到该屏幕，请用 /mv list 查看可用屏幕。");

        public Messages {
            prefix = blankTo(prefix, DEFAULTS.prefix);
            clientDownloadUrl = trimTo(clientDownloadUrl, Protocol.MAX_URL, DEFAULTS.clientDownloadUrl);
            noClient = blankTo(noClient, DEFAULTS.noClient);
            noPermission = blankTo(noPermission, DEFAULTS.noPermission);
            screenNotFound = blankTo(screenNotFound, DEFAULTS.screenNotFound);
        }
    }

    /** 全默认配置。 */
    public static Settings defaults() {
        return new Settings(Limits.DEFAULTS, SyncSettings.DEFAULTS, Media.DEFAULTS,
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
                intOf(root, "limits.max-total-screens", Protocol.MAX_SCREEN_COUNT, 1, Protocol.MAX_SCREEN_COUNT));

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

        StorageSettings storage = new StorageSettings(
                longOf(root, "storage.save-debounce-ms", 1500L, 100L, 60000L));

        Messages messages = new Messages(
                root.getString("messages.prefix"),
                root.getString("messages.client-download-url"),
                root.getString("messages.no-client"),
                root.getString("messages.no-permission"),
                root.getString("messages.screen-not-found"));

        return new Settings(limits, sync, media, storage, messages);
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
