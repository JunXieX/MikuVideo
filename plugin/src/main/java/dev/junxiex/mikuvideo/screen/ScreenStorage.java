package dev.junxiex.mikuvideo.screen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.junxiex.mikuvideo.config.Settings;
import dev.junxiex.mikuvideo.playback.PlaybackService;
import dev.junxiex.mikuvideo.protocol.Playback;
import dev.junxiex.mikuvideo.util.Tasks;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * 屏幕数据的 JSON 持久化。
 *
 * <p>写入流程固定为：写 {@code .tmp} → 旧文件转存 {@code .bak} → 原子替换正式文件，
 * 中途断电最多丢掉最后一次改动，不会出现半截 JSON。
 * 变更通过版本号合并：短时间内多次改动只落盘一次，关服时同步 flush。</p>
 *
 * @author JunXieX
 */
public final class ScreenStorage implements ScreenManager.Listener {

    /** 数据格式版本，字段变更时递增。 */
    public static final int DATA_VERSION = 1;

    /** 载入结果。 */
    public record Loaded(Screen screen, Playback playback) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Plugin plugin;
    private final ScreenManager screens;
    private final PlaybackService playback;
    private final Supplier<Settings> settings;
    private final Tasks tasks;
    private final Path file;
    private final Path tmp;
    private final Path bak;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong version = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile boolean closed;

    public ScreenStorage(Plugin plugin, ScreenManager screens, PlaybackService playback,
                         Supplier<Settings> settings, Tasks tasks) {
        this.plugin = plugin;
        this.screens = screens;
        this.playback = playback;
        this.settings = settings == null ? Settings::defaults : settings;
        this.tasks = tasks;
        Path folder = plugin.getDataFolder().toPath();
        this.file = folder.resolve("screens.json");
        this.tmp = folder.resolve("screens.json.tmp");
        this.bak = folder.resolve("screens.json.bak");
    }

    /** 启动时同步载入；坏文件会被改名备份后继续启动。 */
    public List<Loaded> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return List.of();
            }
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            int dataVersion = optInt(root, "version", DATA_VERSION);
            if (dataVersion != DATA_VERSION) {
                plugin.getLogger().warning("screens.json 版本为 " + dataVersion + "，当前读取器版本 " + DATA_VERSION + "，按兼容方式读取");
            }
            JsonElement elements = root.get("screens");
            if (elements == null || !elements.isJsonArray()) {
                plugin.getLogger().warning("screens.json 缺少 screens 数组，按空数据启动");
                return List.of();
            }
            JsonArray array = elements.getAsJsonArray();
            List<Loaded> loaded = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                try {
                    Loaded entry = readScreen(element.getAsJsonObject());
                    if (entry != null) {
                        loaded.add(entry);
                    }
                } catch (RuntimeException error) {
                    plugin.getLogger().warning("跳过一条无法解析的屏幕记录: " + error.getMessage());
                }
            }
            return List.copyOf(loaded);
        } catch (IOException | RuntimeException error) {
            backupBroken();
            plugin.getLogger().log(Level.SEVERE, "读取 screens.json 失败，已备份坏文件并按空数据启动", error);
            return List.of();
        }
    }

    /** 标记有新变更，按防抖延迟合并写盘。 */
    public void requestSave() {
        if (closed) {
            return;
        }
        version.incrementAndGet();
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        long debounce = Math.max(100L, settings.get().storage().saveDebounceMs());
        tasks.asyncLater(this::drain, debounce);
    }

    /** 同步落盘，最多等待给定毫秒；关服与 admin reload 使用。 */
    public boolean flush(long timeoutMillis) {
        boolean locked = false;
        try {
            locked = writeLock.tryLock(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
            if (!locked) {
                plugin.getLogger().warning("等待写盘锁超时，本次改动可能未保存");
                return false;
            }
            long target = version.get();
            boolean ok = writeLocked();
            if (ok) {
                written.set(target);
            }
            return ok;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (locked) {
                writeLock.unlock();
            }
        }
    }

    /** 关服：先停调度再 flush，最多等 5 秒。 */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        flush(5000L);
    }

    @Override
    public void onScreenCreated(Screen screen) {
        requestSave();
    }

    @Override
    public void onScreenUpdated(Screen screen) {
        requestSave();
    }

    @Override
    public void onScreenRemoved(Screen screen) {
        requestSave();
    }

    private void drain() {
        scheduled.set(false);
        if (closed) {
            return;
        }
        long target = version.get();
        if (target <= written.get()) {
            return;
        }
        if (writeLock.tryLock()) {
            try {
                if (writeLocked()) {
                    written.set(target);
                }
            } finally {
                writeLock.unlock();
            }
        }
        if (version.get() > written.get() && !closed && scheduled.compareAndSet(false, true)) {
            tasks.asyncLater(this::drain, Math.max(100L, settings.get().storage().saveDebounceMs()));
        }
    }

    private boolean writeLocked() {
        try {
            Files.createDirectories(file.getParent());
            String payload = GSON.toJson(snapshot());
            Files.writeString(tmp, payload, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (Files.exists(file)) {
                try {
                    Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException error) {
                    plugin.getLogger().warning("备份旧 screens.json 失败: " + error.getMessage());
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().log(Level.SEVERE, "写入 screens.json 失败", error);
            return false;
        }
    }

    private JsonObject snapshot() {
        JsonObject root = new JsonObject();
        root.addProperty("version", DATA_VERSION);
        JsonArray array = new JsonArray();
        for (Screen screen : screens.all()) {
            array.add(writeScreen(screen, playback.stateOf(screen)));
        }
        root.add("screens", array);
        return root;
    }

    private JsonObject writeScreen(Screen screen, Playback state) {
        JsonObject object = new JsonObject();
        object.addProperty("id", screen.id());
        object.addProperty("name", screen.name());
        object.addProperty("owner", screen.owner().toString());
        object.addProperty("world", screen.world());
        object.add("center", vector(screen.cx(), screen.cy(), screen.cz()));
        object.add("normal", vector(screen.nx(), screen.ny(), screen.nz()));
        object.add("right", vector(screen.rx(), screen.ry(), screen.rz()));
        object.add("up", vector(screen.ux(), screen.uy(), screen.uz()));
        object.addProperty("width", screen.width());
        object.addProperty("height", screen.height());
        object.addProperty("volume", screen.volume());
        object.addProperty("public", screen.publicScreen());
        object.addProperty("createdAt", screen.createdAt());
        object.add("playback", writePlayback(state));
        return object;
    }

    private JsonObject writePlayback(Playback state) {
        JsonObject object = new JsonObject();
        object.addProperty("url", state.url());
        JsonObject headers = new JsonObject();
        for (Map.Entry<String, String> entry : state.headers().entrySet()) {
            headers.addProperty(entry.getKey(), entry.getValue());
        }
        object.add("headers", headers);
        object.addProperty("positionMillis", state.positionMillis());
        object.addProperty("startEpochMillis", state.startEpochMillis());
        object.addProperty("paused", state.paused());
        object.addProperty("rate", state.rate());
        object.addProperty("volume", state.volume());
        object.addProperty("audioEnabled", state.audioEnabled());
        object.addProperty("durationMillis", state.durationMillis());
        return object;
    }

    private Loaded readScreen(JsonObject object) {
        String id = optString(object, "id", "");
        if (id.isBlank() || id.length() > 32) {
            return null;
        }
        String name = optString(object, "name", id);
        if (name.isBlank()) {
            name = id;
        }
        UUID owner;
        try {
            owner = UUID.fromString(optString(object, "owner", ""));
        } catch (IllegalArgumentException error) {
            plugin.getLogger().warning("屏幕 " + id + " 的 owner 非法，已跳过");
            return null;
        }
        String world = optString(object, "world", "");
        if (world.isBlank()) {
            plugin.getLogger().warning("屏幕 " + id + " 缺少世界名，已跳过");
            return null;
        }
        double[] center = vectorOf(object.get("center"));
        double[] normal = vectorOf(object.get("normal"));
        double[] right = vectorOf(object.get("right"));
        double[] up = vectorOf(object.get("up"));
        if (center == null || normal == null || right == null || up == null) {
            plugin.getLogger().warning("屏幕 " + id + " 的几何数据不完整，已跳过");
            return null;
        }
        Screen screen = new Screen(id, name, owner, world,
                center[0], center[1], center[2],
                normal[0], normal[1], normal[2],
                right[0], right[1], right[2],
                up[0], up[1], up[2],
                (float) optDouble(object, "width", 4.0D),
                (float) optDouble(object, "height", 2.25D),
                optInt(object, "volume", 80),
                optBoolean(object, "public", true),
                optLong(object, "createdAt", System.currentTimeMillis()));
        Playback state = readPlayback(object.getAsJsonObject("playback"), screen.volume());
        return new Loaded(screen, state);
    }

    private Playback readPlayback(JsonObject object, int fallbackVolume) {
        if (object == null) {
            return Playback.stopped().withVolume(fallbackVolume);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        JsonElement headerElement = object.get("headers");
        if (headerElement != null && headerElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : headerElement.getAsJsonObject().entrySet()) {
                if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                    headers.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        return new Playback(
                optString(object, "url", ""),
                headers,
                optLong(object, "positionMillis", 0L),
                optLong(object, "startEpochMillis", 0L),
                optBoolean(object, "paused", true),
                (float) optDouble(object, "rate", 1.0D),
                optInt(object, "volume", fallbackVolume),
                optBoolean(object, "audioEnabled", false),
                optLong(object, "durationMillis", 0L));
    }

    private void backupBroken() {
        if (!Files.exists(file)) {
            return;
        }
        Path target = file.resolveSibling("screens.json.bad-" + System.currentTimeMillis());
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("坏数据文件已备份为 " + target.getFileName());
        } catch (IOException error) {
            plugin.getLogger().log(Level.SEVERE, "备份坏数据文件失败", error);
        }
    }

    private static JsonObject vector(double x, double y, double z) {
        JsonObject object = new JsonObject();
        object.addProperty("x", x);
        object.addProperty("y", y);
        object.addProperty("z", z);
        return object;
    }

    private static double[] vectorOf(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        double x = optDouble(object, "x", Double.NaN);
        double y = optDouble(object, "y", Double.NaN);
        double z = optDouble(object, "z", Double.NaN);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        return new double[]{x, y, z};
    }

    private static String optString(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static int optInt(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static long optLong(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static double optDouble(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static boolean optBoolean(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException error) {
            return fallback;
        }
    }
}