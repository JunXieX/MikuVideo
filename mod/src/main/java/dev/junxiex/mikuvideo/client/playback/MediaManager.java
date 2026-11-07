package dev.junxiex.mikuvideo.client.playback;

import dev.junxiex.mikuvideo.client.media.FfmpegMediaEngine;
import dev.junxiex.mikuvideo.client.media.MediaEngine;
import dev.junxiex.mikuvideo.client.media.MediaRequest;
import dev.junxiex.mikuvideo.client.media.MediaSession;
import dev.junxiex.mikuvideo.client.media.MediaState;
import dev.junxiex.mikuvideo.client.media.MediaResolverService;
import dev.junxiex.mikuvideo.client.media.resolve.ResolvedMedia;

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
    private final MediaResolverService resolverService;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Set<String> resolving = ConcurrentHashMap.newKeySet();
    private int tickCounter;

    private MediaManager() {
        Path gameDirectory = FabricLoader.getInstance().getGameDir();
        this.engine = new FfmpegMediaEngine(gameDirectory);
        this.resolverService = new MediaResolverService(gameDirectory);
    }

    public MediaEngine engine() {
        return engine;
    }

    public MediaResolverService resolver() {
        return resolverService;
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
        if (screen.failureCount() >= 3) {
            return;
        }
        String url = playback.url();
        if (resolverService.looksLikePage(url) && screen.resolved(url) == null) {
            if (screen.lastFailureAt() + 5_000L <= System.currentTimeMillis()) {
                resolve(screen, config, url);
            }
            return;
        }
        long serverNow = ClockSync.serverNow();
        ResolvedMedia resolved = screen.resolved(url);
        boolean live = resolved != null && resolved.live();
        long expected = playback.positionAt(serverNow);
        if (live) {
            // 直播没有可对齐的时间轴：既有位置对直播毫无意义，-ss 还会把流拖到窗口外
            expected = 0L;
        } else if (playback.durationMillis() > 0L) {
            expected = Math.min(expected, playback.durationMillis());
        }
        int width = decodeWidth(info, config);
        int height = decodeHeight(info, config, width);
        MediaRequest request = resolved == null
                ? new MediaRequest(url, playback.headers(), Math.max(0L, expected), width, height, config.fps, config.audioEnabled)
                : resolved.toRequest(Math.max(0L, expected), width, height, config.fps, config.audioEnabled, playback.headers());
        String key = request.url() + "|" + request.audioUrl() + "|" + request.headers().hashCode()
                + "|" + width + "x" + height + "|" + config.fps + "|" + config.audioEnabled;
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
            entry = new Entry(session, key, live);
            entries.put(info.id(), entry);
            session.start(request, screen::offerFrame, (state, detail) -> screen.setMediaState(state, detail));
            if (playback.paused()) {
                session.pause();
            }
            if (resolved != null && resolved.durationMillis() > 0L && playback.durationMillis() <= 0L
                    && screen.shouldReportDuration(url)) {
                screen.markDurationReported(url);
                ClientNetworking.send(new Message.DurationC2S(info.id(), url, resolved.durationMillis()));
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
        boolean forceSeek = screen.consumeForceSeek();
        // 直播一律不 seek：FLV 直播没有索引，HLS 直播窗口只有几十秒，跳一次就是长时间黑屏
        if (!live && forceSeek) {
            session.seek(expected);
            entry.lastSeekAt = now;
        } else if (!live && !playback.paused() && entry.lastSeekAt + SEEK_COOLDOWN_MS < now) {
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
            // 直播地址会过期或被 CDN 轮换，缓存下来的解析结果不能直接复用，丢掉后重新解析
            if (resolved != null && resolved.live()) {
                screen.clearResolved();
                screen.resetFailures();
            }
            return;
        }
        long duration = session.durationMillis();
        if (duration > 0L && screen.shouldReportDuration(playback.url())) {
            screen.markDurationReported(playback.url());
            ClientNetworking.send(new Message.DurationC2S(info.id(), url, duration));
        }
    }

    private void resolve(ClientScreen screen, ClientConfig config, String url) {
        String id = screen.info().id();
        if (!resolving.add(id)) {
            return;
        }
        screen.setMediaState(MediaState.PREPARING, "正在解析视频地址…");
        Path cookies = null;
        if (config.cookiesFile != null && !config.cookiesFile.isBlank()) {
            try {
                cookies = Path.of(config.cookiesFile);
            } catch (RuntimeException ignored) {
                cookies = null;
            }
        }
        resolverService.resolve(url, cookies, config.qualityHeight, config.proxy)
                .whenComplete((media, error) -> Minecraft.getInstance().execute(() -> {
                    resolving.remove(id);
                    if (error != null) {
                        String message = error.getCause() != null && error.getCause().getMessage() != null
                                ? error.getCause().getMessage() : String.valueOf(error.getMessage());
                        screen.setMediaState(MediaState.ERROR, "解析失败：" + message);
                        if (screen.shouldReportError(url)) {
                            screen.markErrorReported(url);
                            ClientNetworking.send(new Message.MediaErrorC2S(id, 7, "解析失败：" + message));
                        }
                    } else {
                        screen.setResolved(url, media);
                        screen.resetFailures();
                    }
                }));
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
        resolving.remove(screenId);
        Entry entry = entries.remove(screenId);
        if (entry != null) {
            entry.session.close();
        }
    }

    public void closeAll() {
        resolving.clear();
        for (String id : List.copyOf(entries.keySet())) {
            close(id);
        }
    }

    /** 诊断输出：每块正在解码的屏幕一行，供 /mikuvideo-client diag 使用。 */
    public List<String> diagnostics() {
        if (entries.isEmpty()) {
            return List.of("当前没有正在解码的屏幕");
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Entry> pair : entries.entrySet()) {
            Entry entry = pair.getValue();
            MediaSession session = entry.session;
            StringBuilder line = new StringBuilder()
                    .append(pair.getKey())
                    .append(" | ").append(entry.live ? "直播" : "点播")
                    .append(" | ").append(session.state())
                    .append(" | 位置 ").append(session.positionMillis() / 1000L).append('s');
            long duration = session.durationMillis();
            if (duration > 0L) {
                line.append('/').append(duration / 1000L).append('s');
            }
            String message = session.lastError();
            if (message != null && !message.isBlank()) {
                line.append(" | ").append(message.length() > 90 ? message.substring(0, 90) + "…" : message);
            }
            lines.add(line.toString());
        }
        return List.copyOf(lines);
    }

    public MediaState stateOf(String screenId) {
        Entry entry = entries.get(screenId);
        return entry == null ? MediaState.IDLE : entry.session.state();
    }

    private static final class Entry {
        private final MediaSession session;
        private final String key;
        private final boolean live;
        private long lastSeekAt;

        private Entry(MediaSession session, String key, boolean live) {
            this.session = session;
            this.key = key;
            this.live = live;
        }
    }
}