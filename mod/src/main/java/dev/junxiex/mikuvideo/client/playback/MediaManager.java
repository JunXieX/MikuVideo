package dev.junxiex.mikuvideo.client.playback;

import dev.junxiex.mikuvideo.client.media.FfmpegMediaEngine;
import dev.junxiex.mikuvideo.client.media.MediaEngine;
import dev.junxiex.mikuvideo.client.media.MediaRequest;
import dev.junxiex.mikuvideo.client.media.MediaSession;
import dev.junxiex.mikuvideo.client.media.MediaState;

import dev.junxiex.mikuvideo.client.MikuVideoClient;
import dev.junxiex.mikuvideo.client.config.ClientConfig;
import dev.junxiex.mikuvideo.client.net.ClientNetworking;
import dev.junxiex.mikuvideo.client.net.ClockSync;
import dev.junxiex.mikuvideo.client.screen.ClientScreen;
import dev.junxiex.mikuvideo.client.screen.ScreenRegistry;
import dev.junxiex.mikuvideo.client.util.ClientUrls;
import dev.junxiex.mikuvideo.protocol.Message;
import dev.junxiex.mikuvideo.protocol.Playback;
import dev.junxiex.mikuvideo.protocol.ScreenInfo;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 管理每块屏幕的解码会话与客户端解码预算。 */
public final class MediaManager {
    public static final MediaManager INSTANCE = new MediaManager();

    private static final long SEEK_COOLDOWN_MS = 7_000L;
    private static final long BEHIND_TOLERANCE_MS = 1_500L;
    private static final long AHEAD_TOLERANCE_MS = 3_500L;

    private final MediaEngine engine;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private int tickCounter;

    private MediaManager() {
        Path gameDirectory = FabricLoader.getInstance().getGameDir();
        this.engine = new FfmpegMediaEngine(gameDirectory);
    }

    public MediaEngine engine() {
        return engine;
    }

    public void tick(Minecraft minecraft) {
        tickCounter++;
        if (tickCounter % 10 != 0) {
            return;
        }
        if (minecraft.level == null || minecraft.player == null) {
            closeAll();
            return;
        }
        ClientConfig config = MikuVideoClient.config();
        String dimension = minecraft.level.dimension().identifier().toString();
        Vec3 camera = minecraft.player.getEyePosition(1.0F);
        List<ClientScreen> candidates = new ArrayList<>();
        for (ClientScreen screen : ScreenRegistry.INSTANCE.inWorld(dimension)) {
            Playback playback = screen.playback();
            if (playback == null || playback.isStopped()) {
                continue;
            }
            ScreenInfo info = screen.info();
            double dx = info.x() - camera.x;
            double dy = info.y() - camera.y;
            double dz = info.z() - camera.z;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > config.viewDistance * config.viewDistance) {
                continue;
            }
            screen.distanceSq = distanceSq;
            candidates.add(screen);
        }
        candidates.sort(Comparator.comparingDouble(screen -> screen.distanceSq));

        Set<String> active = new HashSet<>();
        int limit = Math.min(config.maxActiveStreams, candidates.size());
        for (int index = 0; index < limit; index++) {
            active.add(candidates.get(index).info().id());
        }
        for (String id : List.copyOf(entries.keySet())) {
            if (!active.contains(id)) {
                close(id);
            }
        }
        for (ClientScreen screen : candidates) {
            if (active.contains(screen.info().id())) {
                ensureSession(screen, config, camera);
            }
        }
    }

    private void ensureSession(ClientScreen screen, ClientConfig config, Vec3 camera) {
        Playback playback = screen.playback();
        if (playback == null || playback.isStopped()) {
            close(screen.info().id());
            return;
        }
        if (screen.isEnded(playback.url())) {
            close(screen.info().id());
            return;
        }
        ScreenInfo info = screen.info();
        if (!ClientUrls.isAllowed(playback.url(), config.allowPrivateHosts)) {
            screen.setMediaState(MediaState.ERROR, "客户端拒绝本机/内网地址");
            if (screen.shouldReportError(playback.url())) {
                screen.markErrorReported(playback.url());
                ClientNetworking.send(new Message.MediaErrorC2S(info.id(), 6, "客户端拒绝本机/内网地址"));
            }
            return;
        }
        long serverNow = ClockSync.serverNow();
        long expected = playback.positionAt(serverNow);
        if (playback.durationMillis() > 0L) {
            expected = Math.min(expected, playback.durationMillis());
        }
        int width = decodeWidth(info, config);
        int height = decodeHeight(info, config, width);
        String key = playback.url() + "|" + playback.headers().hashCode() + "|" + width + "x" + height + "|" + config.fps + "|" + config.audioEnabled;
        Entry entry = entries.get(info.id());
        if (entry == null && screen.failureCount() >= 3) {
            return;
        }
        if (entry != null && !entry.key.equals(key)) {
            close(info.id());
            entry = null;
        }
        if (entry == null) {
            MediaSession session = engine.createSession();
            entry = new Entry(session, key);
            entries.put(info.id(), entry);
            long start = Math.max(0L, expected);
            session.start(new MediaRequest(playback.url(), playback.headers(), start, width, height, config.fps, config.audioEnabled),
                    screen::offerFrame,
                    (state, detail) -> screen.setMediaState(state, detail));
            if (playback.paused()) {
                session.pause();
            }
        }
        MediaSession session = entry.session;
        float distance = (float) Math.sqrt(Math.max(0.0, screen.distanceSq));
        float attenuation = distance <= 3.0F ? 1.0F : (float) Math.max(0.0, 1.0 - (distance - 3.0F) / Math.max(1.0, config.viewDistance - 3.0));
        float volume = (config.masterVolume / 100.0F) * (playback.volume() / 100.0F) * attenuation;
        session.setVolume(playback.paused() ? 0.0F : volume);
        if (playback.paused()) {
            session.pause();
        } else {
            session.resume();
        }

        if (session.isEnded() || (playback.durationMillis() > 0L && expected >= playback.durationMillis())) {
            screen.markEnded(playback.url(), expected);
            close(info.id());
            return;
        }
        long now = System.currentTimeMillis();
        if (!playback.paused() && entry.lastSeekAt + SEEK_COOLDOWN_MS < now) {
            long actual = session.positionMillis();
            if (actual > 0L && (expected - actual > BEHIND_TOLERANCE_MS || actual - expected > AHEAD_TOLERANCE_MS)) {
                session.seek(expected);
                entry.lastSeekAt = now;
            }
        }
        if (session.state() == MediaState.ERROR && screen.shouldReportError(playback.url())) {
            screen.markErrorReported(playback.url());
            ClientNetworking.send(new Message.MediaErrorC2S(info.id(), 5, session.lastError()));
        }
        if (session.state() == MediaState.ERROR && screen.failureCount() < 3
                && screen.lastFailureAt() + 10_000L < System.currentTimeMillis()) {
            close(info.id());
            return;
        }
        long duration = session.durationMillis();
        if (duration > 0L && screen.shouldReportDuration(playback.url())) {
            screen.markDurationReported(playback.url());
            ClientNetworking.send(new Message.DurationC2S(info.id(), duration));
        }
    }

    private static int decodeWidth(ScreenInfo info, ClientConfig config) {
        int width = Math.round(info.width() * config.pixelsPerBlock);
        width = Math.max(128, Math.min(config.maxWidth, width));
        return width - (width % 2);
    }

    private static int decodeHeight(ScreenInfo info, ClientConfig config, int width) {
        double aspect = info.height() / Math.max(0.01F, info.width());
        int height = Math.round((float) (width * aspect));
        height = Math.max(72, Math.min(config.maxHeight, height));
        return height - (height % 2);
    }

    public void close(String screenId) {
        Entry entry = entries.remove(screenId);
        if (entry != null) {
            entry.session.close();
        }
    }

    public void closeAll() {
        for (String id : List.copyOf(entries.keySet())) {
            close(id);
        }
    }

    public MediaState stateOf(String screenId) {
        Entry entry = entries.get(screenId);
        return entry == null ? MediaState.IDLE : entry.session.state();
    }

    private static final class Entry {
        private final MediaSession session;
        private final String key;
        private long lastSeekAt;

        private Entry(MediaSession session, String key) {
            this.session = session;
            this.key = key;
        }
    }
}