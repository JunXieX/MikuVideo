package dev.junxiex.mikuvideo.client.screen;

import dev.junxiex.mikuvideo.client.playback.MediaManager;
import dev.junxiex.mikuvideo.protocol.ScreenInfo;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 当前连接内的屏幕集合。 */
public final class ScreenRegistry {
    public static final ScreenRegistry INSTANCE = new ScreenRegistry();

    private final ConcurrentHashMap<String, ClientScreen> screens = new ConcurrentHashMap<>();

    private ScreenRegistry() {
    }

    public void replaceAll(List<ScreenInfo> list) {
        Set<String> incoming = new HashSet<>();
        for (ScreenInfo info : list) {
            incoming.add(info.id());
        }
        for (String id : List.copyOf(screens.keySet())) {
            if (!incoming.contains(id)) {
                remove(id);
            }
        }
        for (ScreenInfo info : list) {
            upsert(info);
        }
    }

    public void upsert(ScreenInfo info) {
        screens.compute(info.id(), (id, existing) -> {
            if (existing == null) {
                ClientScreen created = new ClientScreen(info);
                try {
                    created.ensurePlaceholder(Minecraft.getInstance());
                } catch (RuntimeException ignored) {
                    // 纹理创建失败时渲染层会跳过该屏幕，下一帧再重试。
                }
                return created;
            }
            existing.setInfo(info);
            return existing;
        });
    }

    public void remove(String id) {
        ClientScreen screen = screens.remove(id);
        if (screen != null) {
            MediaManager.INSTANCE.close(id);
            screen.close(Minecraft.getInstance());
        }
    }

    public void clear() {
        for (String id : List.copyOf(screens.keySet())) {
            remove(id);
        }
    }

    public void uploadFrames(Minecraft minecraft) {
        for (ClientScreen screen : screens.values()) {
            try {
                screen.uploadFrame(minecraft);
            } catch (RuntimeException ignored) {
                // 单块屏幕的纹理异常不能影响其他屏幕。
            }
        }
    }

    public Collection<ClientScreen> all() {
        return screens.values();
    }

    public ClientScreen get(String id) {
        return screens.get(id);
    }

    public List<ClientScreen> inWorld(String dimension) {
        List<ClientScreen> result = new ArrayList<>();
        for (ClientScreen screen : screens.values()) {
            if (screen.info().world().equals(dimension)) {
                result.add(screen);
            }
        }
        return result;
    }

    public int size() {
        return screens.size();
    }
}