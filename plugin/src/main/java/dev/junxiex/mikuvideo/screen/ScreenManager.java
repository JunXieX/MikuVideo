package dev.junxiex.mikuvideo.screen;

import dev.junxiex.mikuvideo.config.Settings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 屏幕注册表。
 *
 * <p>主表与索引都是并发容器，写入串行化在一把细粒度锁内完成，
 * 读取只取快照，因此区域线程、Netty 线程、异步写盘线程都能安全访问。</p>
 *
 * @author JunXieX
 */
public final class ScreenManager {

    /** ID 长度：8 位小写 base36。 */
    public static final int ID_LENGTH = 8;
    private static final long ID_SPACE = 2821109907456L; // 36^8
    /** 同一面但相邻方块的屏幕中心距恰好 1.0，阈值必须小于它，否则拼屏会被判成重复。 */
    private static final double DUPLICATE_DISTANCE = 0.75D;
    private static final double DUPLICATE_NORMAL_DOT = 0.99D;

    /** 屏幕集合变更的监听者，广播与持久化都挂在这里。 */
    public interface Listener {
        default void onScreenCreated(Screen screen) {
        }

        default void onScreenUpdated(Screen screen) {
        }

        default void onScreenRemoved(Screen screen) {
        }
    }

    /** 创建失败原因。 */
    public enum CreateError {
        /** 成功。 */
        NONE,
        /** 数量超限。 */
        LIMIT,
        /** 位置与朝向重复。 */
        DUPLICATE,
        /** 参数非法。 */
        INVALID
    }

    /** 创建结果。 */
    public record Creation(Screen screen, CreateError error) {
        public boolean ok() {
            return error == CreateError.NONE && screen != null;
        }
    }

    /** 命令解析结果：成功时带屏幕，失败时带可读原因。 */
    public record Resolution(Screen screen, String error) {
        public boolean ok() {
            return screen != null;
        }

        public static Resolution found(Screen screen) {
            return new Resolution(screen, "");
        }

        public static Resolution fail(String error) {
            return new Resolution(null, error);
        }
    }

    private final Map<String, Screen> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> byOwner = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> byWorld = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Supplier<Settings> settings;

    public ScreenManager(Supplier<Settings> settings) {
        this.settings = settings == null ? Settings::defaults : settings;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public int total() {
        return byId.size();
    }

    public int countOwned(UUID owner) {
        if (owner == null) {
            return 0;
        }
        Set<String> owned = byOwner.get(owner);
        return owned == null ? 0 : owned.size();
    }

    public Optional<Screen> get(String id) {
        if (id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id));
    }

    /** 全量快照，按创建时间升序。 */
    public List<Screen> all() {
        List<Screen> snapshot = new ArrayList<>(byId.values());
        snapshot.sort(Comparator.comparingLong(Screen::createdAt).thenComparing(Screen::id));
        return List.copyOf(snapshot);
    }

    public List<Screen> ownedBy(UUID owner) {
        if (owner == null) {
            return List.of();
        }
        Set<String> ids = byOwner.get(owner);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Screen> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            Screen screen = byId.get(id);
            if (screen != null) {
                result.add(screen);
            }
        }
        result.sort(Comparator.comparingLong(Screen::createdAt));
        return List.copyOf(result);
    }

    public List<Screen> inWorld(String world) {
        if (world == null) {
            return List.of();
        }
        Set<String> ids = byWorld.get(world);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Screen> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            Screen screen = byId.get(id);
            if (screen != null) {
                result.add(screen);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 创建屏幕，写入成功后统一触发监听。
     *
     * @param maxOwned 该玩家允许拥有的屏幕数；{@code <= 0} 表示不限制
     */
    public Creation create(UUID owner, String name, ScreenGeometry.Hit hit, float width, float height,
                           int volume, int maxOwned) {
        if (owner == null || hit == null || name == null) {
            return new Creation(null, CreateError.INVALID);
        }
        Settings.Limits limits = settings.get().limits();
        Screen created;
        synchronized (lock) {
            if (byId.size() >= limits.maxTotalScreens()) {
                return new Creation(null, CreateError.LIMIT);
            }
            if (maxOwned > 0 && countOwned(owner) >= maxOwned) {
                return new Creation(null, CreateError.LIMIT);
            }
            for (Screen existing : inWorld(hit.world())) {
                if (isDuplicate(existing, hit)) {
                    return new Creation(null, CreateError.DUPLICATE);
                }
            }
            created = Screen.create(nextId(), name, owner, hit, width, height, volume, System.currentTimeMillis());
            index(created);
            byId.put(created.id(), created);
        }
        notifyCreated(created);
        return new Creation(created, CreateError.NONE);
    }

    /** 覆盖更新；ID 必须已存在。 */
    public Optional<Screen> replace(Screen updated) {
        if (updated == null) {
            return Optional.empty();
        }
        boolean changed;
        synchronized (lock) {
            Screen old = byId.get(updated.id());
            if (old == null) {
                return Optional.empty();
            }
            if (old.equals(updated)) {
                return Optional.of(old);
            }
            unindex(old);
            index(updated);
            byId.put(updated.id(), updated);
            changed = true;
        }
        if (changed) {
            notifyUpdated(updated);
        }
        return Optional.of(updated);
    }

    /** 删除单块屏幕。 */
    public Optional<Screen> remove(String id) {
        if (id == null) {
            return Optional.empty();
        }
        Screen removed;
        synchronized (lock) {
            removed = byId.remove(id);
            if (removed == null) {
                return Optional.empty();
            }
            unindex(removed);
        }
        notifyRemoved(removed);
        return Optional.of(removed);
    }

    /** 删除某个世界的全部屏幕，返回被删除的列表。 */
    public List<Screen> removeWorld(String world) {
        if (world == null) {
            return List.of();
        }
        List<Screen> removed = new ArrayList<>();
        synchronized (lock) {
            for (Screen screen : inWorld(world)) {
                Screen current = byId.remove(screen.id());
                if (current != null) {
                    unindex(current);
                    removed.add(current);
                }
            }
        }
        for (Screen screen : removed) {
            notifyRemoved(screen);
        }
        return List.copyOf(removed);
    }

    /** 载入阶段导入，不触发监听，避免启动时刷屏广播。 */
    public void importScreen(Screen screen) {
        if (screen == null) {
            return;
        }
        synchronized (lock) {
            if (byId.containsKey(screen.id())) {
                return;
            }
            index(screen);
            byId.put(screen.id(), screen);
        }
    }

    /**
     * 命令参数解析：先按完整 ID，再按 ID 前缀，最后按名称。
     *
     * @param token  参数
     * @param viewer 发起者，控制台传 null
     * @param all    是否有跨玩家管理权限
     */
    public Resolution resolve(String token, UUID viewer, boolean all) {
        if (token == null || token.isBlank()) {
            return Resolution.fail("请输入屏幕 ID 或名称");
        }
        String key = token.trim();
        Screen exact = byId.get(key);
        if (exact != null) {
            return visible(exact, viewer, all) ? Resolution.found(exact) : Resolution.fail("无权访问该屏幕");
        }
        String prefix = key.toLowerCase(Locale.ROOT);
        List<Screen> matches = new ArrayList<>(2);
        for (Screen screen : byId.values()) {
            if (screen.id().startsWith(prefix) && visible(screen, viewer, all)) {
                matches.add(screen);
            }
        }
        if (matches.isEmpty()) {
            matches = byName(key, viewer, all);
        }
        if (matches.isEmpty()) {
            return Resolution.fail("找不到该屏幕");
        }
        if (matches.size() > 1) {
            Screen own = pickOwn(matches, viewer);
            if (own != null) {
                return Resolution.found(own);
            }
            return Resolution.fail("匹配到多块屏幕，请用更完整的 ID 前缀");
        }
        return Resolution.found(matches.get(0));
    }

    /** 屏幕是否对指定玩家可见（不考虑距离）。 */
    public boolean visible(Screen screen, UUID viewer, boolean all) {
        if (screen == null) {
            return false;
        }
        if (screen.publicScreen() || all) {
            return true;
        }
        return viewer != null && screen.ownedBy(viewer);
    }

    private List<Screen> byName(String name, UUID viewer, boolean all) {
        List<Screen> result = new ArrayList<>(2);
        for (Screen screen : byId.values()) {
            if (screen.name().equalsIgnoreCase(name) && visible(screen, viewer, all)) {
                result.add(screen);
            }
        }
        return result;
    }

    private Screen pickOwn(List<Screen> matches, UUID viewer) {
        if (viewer == null) {
            return null;
        }
        for (Screen screen : matches) {
            if (screen.ownedBy(viewer)) {
                return screen;
            }
        }
        return null;
    }

    /** 该位置是否已有同朝向的屏幕；{@code excludeId} 用于移动自身时跳过自己。 */
    public boolean occupied(ScreenGeometry.Hit hit, String excludeId) {
        for (Screen existing : inWorld(hit.world())) {
            if (!existing.id().equals(excludeId) && isDuplicate(existing, hit)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDuplicate(Screen existing, ScreenGeometry.Hit hit) {
        if (!existing.world().equals(hit.world())) {
            return false;
        }
        double dx = existing.cx() - hit.cx();
        double dy = existing.cy() - hit.cy();
        double dz = existing.cz() - hit.cz();
        if (dx * dx + dy * dy + dz * dz > DUPLICATE_DISTANCE * DUPLICATE_DISTANCE) {
            return false;
        }
        double dot = existing.nx() * hit.basis().normal().getX()
                + existing.ny() * hit.basis().normal().getY()
                + existing.nz() * hit.basis().normal().getZ();
        return dot >= DUPLICATE_NORMAL_DOT;
    }

    private void index(Screen screen) {
        byOwner.computeIfAbsent(screen.owner(), key -> ConcurrentHashMap.newKeySet()).add(screen.id());
        byWorld.computeIfAbsent(screen.world(), key -> ConcurrentHashMap.newKeySet()).add(screen.id());
    }

    private void unindex(Screen screen) {
        Set<String> owned = byOwner.get(screen.owner());
        if (owned != null) {
            owned.remove(screen.id());
            if (owned.isEmpty()) {
                byOwner.remove(screen.owner(), owned);
            }
        }
        Set<String> world = byWorld.get(screen.world());
        if (world != null) {
            world.remove(screen.id());
            if (world.isEmpty()) {
                byWorld.remove(screen.world(), world);
            }
        }
    }

    private String nextId() {
        for (int attempt = 0; attempt < 128; attempt++) {
            long value = ThreadLocalRandom.current().nextLong(ID_SPACE);
            String candidate = Long.toString(value, 36);
            if (candidate.length() < ID_LENGTH) {
                candidate = "0".repeat(ID_LENGTH - candidate.length()) + candidate;
            }
            if (!byId.containsKey(candidate)) {
                return candidate;
            }
        }
        // 理论上不会走到这里（36^8 空间且上限 512 块），兜底用时间戳保证唯一
        String fallback = Long.toString(System.nanoTime() & 0xFFFFFFFFL, 36);
        return "x" + fallback;
    }

    private void notifyCreated(Screen screen) {
        for (Listener listener : listeners) {
            listener.onScreenCreated(screen);
        }
    }

    private void notifyUpdated(Screen screen) {
        for (Listener listener : listeners) {
            listener.onScreenUpdated(screen);
        }
    }

    private void notifyRemoved(Screen screen) {
        for (Listener listener : listeners) {
            listener.onScreenRemoved(screen);
        }
    }

    /** 供同步层复用的快照工具：把集合转成 ID 集合。 */
    public static Set<String> ids(Collection<Screen> screens) {
        Set<String> result = new HashSet<>(Math.max(4, screens.size()));
        for (Screen screen : screens) {
            result.add(screen.id());
        }
        return result;
    }
}