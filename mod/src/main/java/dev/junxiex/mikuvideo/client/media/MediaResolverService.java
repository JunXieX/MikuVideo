package dev.junxiex.mikuvideo.client.media;

import dev.junxiex.mikuvideo.client.media.douyin.DouyinResolver;
import dev.junxiex.mikuvideo.client.media.resolve.ResolvedMedia;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 页面地址解析服务：B 站与抖音走各自的原生/接口解析，带媒体扩展名的地址直接播放。
 *
 * <p>解析全部在客户端完成，服务端只转发原始 URL。解析结果缓存 5 分钟，
 * 同一屏幕重复进入视野不会重复打接口。</p>
 *
 * @author JunXieX
 */
public final class MediaResolverService {
    private static final long CACHE_MILLIS = 5 * 60 * 1000L;
    private static final Set<String> PAGE_HOSTS = Set.of(
            "bilibili.com", "b23.tv", "douyin.com", "iesdouyin.com");

    private final BilibiliResolver bilibili = new BilibiliResolver();
    private final DouyinResolver douyin = new DouyinResolver();
    private final ExecutorService executor;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile String serverCookie = "";

    public MediaResolverService(Path gameDir) {
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "mikuvideo-resolver");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 服务端下发的 B 站凭证；空表示不使用。 */
    public void setServerCookie(String cookie) {
        this.serverCookie = cookie == null ? "" : cookie.trim();
        cache.clear();
    }

    public boolean hasServerCookie() {
        return !serverCookie.isBlank();
    }

    /** 空值使用内置默认接口，{@code off} 表示关闭抖音视频解析。 */
    public void setDouyinApi(String template) {
        String value = template == null || template.isBlank() ? DouyinResolver.DEFAULT_API : template.trim();
        if (value.equalsIgnoreCase("off")) {
            value = "";
        }
        douyin.setApiTemplate(value);
    }

    public String douyinApi() {
        return douyin.apiTemplate();
    }

    public String statusText() {
        return "B 站：原生（视频/番剧/直播）"
                + "；抖音：直播原生，视频 " + (douyin.apiTemplate().isBlank() ? "已关闭" : "解析接口");
    }

    /** 是否是已支持的网页视频站点。 */
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

    /** B 站关键词搜索；失败时以 CompletionException 返回。 */
    public CompletableFuture<java.util.List<BilibiliSearchItem>> searchBilibili(String keyword, Path cookies, String proxy) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return bilibili.search(keyword, 1, cookies, serverCookie, proxy);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, executor);
    }

    public CompletableFuture<ResolvedMedia> resolve(String url, Path cookies, int maxHeight, String proxy) {
        if (!looksLikePage(url)) {
            return CompletableFuture.completedFuture(direct(url));
        }
        String key = url + "|" + maxHeight + "|" + (cookies == null ? "" : cookies.toAbsolutePath()) + "|" + proxy
                + "|" + douyin.apiTemplate() + "|" + serverCookie.hashCode();
        Cached cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) {
            return CompletableFuture.completedFuture(cached.media);
        }
        return CompletableFuture.supplyAsync(() -> {
            Exception nativeFailure = null;
            if (douyin.supports(url)) {
                try {
                    ResolvedMedia media = douyin.resolve(url, cookies, maxHeight, proxy);
                    cache.put(key, new Cached(media, System.currentTimeMillis() + CACHE_MILLIS));
                    return media;
                } catch (Exception error) {
                    nativeFailure = error;
                }
            }
            if (bilibili.supports(url)) {
                try {
                    ResolvedMedia media = bilibili.resolve(url, cookies, serverCookie, maxHeight, proxy);
                    cache.put(key, new Cached(media, System.currentTimeMillis() + CACHE_MILLIS));
                    return media;
                } catch (Exception error) {
                    nativeFailure = error;
                }
            }
            if (isKnownPage(url)) {
                throw new CompletionException(nativeFailure != null ? nativeFailure
                        : new IOException("暂不支持该站点，仅支持 B 站与抖音"));
            }
            return direct(url);
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
