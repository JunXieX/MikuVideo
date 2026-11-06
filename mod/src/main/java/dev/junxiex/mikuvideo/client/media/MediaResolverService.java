package dev.junxiex.mikuvideo.client.media;

import dev.junxiex.mikuvideo.client.media.resolve.ResolvedMedia;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 页面地址解析服务：已知站点走 yt-dlp，直链直接返回。 */
public final class MediaResolverService {
    private static final long CACHE_MILLIS = 5 * 60 * 1000L;
    private static final Set<String> PAGE_HOSTS = Set.of(
            "bilibili.com", "b23.tv", "youtube.com", "youtu.be", "twitch.tv", "vimeo.com",
            "douyin.com", "kuaishou.com", "ixigua.com", "acfun.cn", "weibo.com", "xiaohongshu.com",
            "nicovideo.jp", "dailymotion.com", "facebook.com", "twitter.com", "x.com");

    private final YtDlpLocator locator;
    private final YtDlpResolver resolver;
    private final ExecutorService executor;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public MediaResolverService(Path gameDir) {
        this.locator = new YtDlpLocator(gameDir);
        this.resolver = new YtDlpResolver(locator);
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "mikuvideo-resolver");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void setYtDlpPath(String path) {
        locator.setPath(path);
    }

    public boolean isYtDlpAvailable() {
        return resolver.available();
    }

    public String statusText() {
        return resolver.statusText();
    }

    /** 是否是已知网页视频站点。 */
    public boolean isKnownPage(String url) {
        String host = hostOf(url);
        if (host == null) {
            return false;
        }
        for (String candidate : PAGE_HOSTS) {
            if (host.equals(candidate) || host.endsWith("." + candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 没有媒体扩展名、需要尝试解析的地址。 */
    public boolean looksLikePage(String url) {
        if (isKnownPage(url)) {
            return true;
        }
        String path = url == null ? "" : url.toLowerCase(Locale.ROOT);
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        return !(path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".flv")
                || path.endsWith(".ts") || path.endsWith(".webm") || path.endsWith(".mkv")
                || path.endsWith(".mov") || path.endsWith(".m4s") || path.endsWith(".mp3")
                || path.endsWith(".m4a") || path.endsWith(".aac") || path.endsWith(".ogg"));
    }

    public CompletableFuture<ResolvedMedia> resolve(String url, Path cookies, int maxHeight, String proxy) {
        if (!looksLikePage(url)) {
            return CompletableFuture.completedFuture(direct(url));
        }
        String key = url + "|" + maxHeight + "|" + (cookies == null ? "" : cookies.toAbsolutePath()) + "|" + proxy;
        Cached cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) {
            return CompletableFuture.completedFuture(cached.media);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                ResolvedMedia media = resolver.resolve(url, cookies, maxHeight, proxy);
                cache.put(key, new Cached(media, System.currentTimeMillis() + CACHE_MILLIS));
                return media;
            } catch (Exception error) {
                if (!isKnownPage(url)) {
                    // 未知站点解析失败时仍按直链尝试，避免误伤普通流地址。
                    return direct(url);
                }
                throw new java.util.concurrent.CompletionException(error);
            }
        }, executor);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static ResolvedMedia direct(String url) {
        return new ResolvedMedia(url, "", Map.of(), Map.of(), 0L, "", false);
    }

    private static String hostOf(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception error) {
            return null;
        }
    }

    private record Cached(ResolvedMedia media, long expiresAt) {
    }
}