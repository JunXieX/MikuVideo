package dev.junxiex.mikuvideo.client.media;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.junxiex.mikuvideo.client.media.resolve.MediaResolver;
import dev.junxiex.mikuvideo.client.media.resolve.ResolvedMedia;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站原生解析：直接调用 B 站 web API（WBI 签名），解析视频 / 番剧 / 直播。
 *
 * <p>相比外部解析器，原生路径更快、可控质量档位，也能把 DASH 音视频的请求头
 * 精确交给 FFmpeg；解析失败时由上层把源站原因直接呈现给玩家。</p>
 *
 * @author JunXieX
 */
public final class BilibiliResolver implements MediaResolver {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final Pattern BV = Pattern.compile("(BV[0-9A-Za-z]{10})");
    private static final Pattern AV = Pattern.compile("/av(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EP = Pattern.compile("/ep(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEASON = Pattern.compile("/ss(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIVE = Pattern.compile("^/(\\d+)");
    private static final int[] QUALITY_RANK = {16, 32, 64, 74, 80, 112, 116, 120, 127};
    /** 直播候选探活的单次超时。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    /** 一轮最多并发探几个候选，够覆盖多 host 又不至于把请求打散。 */
    private static final int MAX_PROBES = 4;
    private static final int MIXIN_TABLE_LENGTH = 32;
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private volatile WbiKeys wbiKeys;
    private volatile long wbiFetchedAt;

    public boolean supports(String url) {
        String host = hostOf(url);
        return host != null && (host.equals("bilibili.com") || host.endsWith(".bilibili.com")
                || host.equals("b23.tv") || host.endsWith(".b23.tv"));
    }

    /** 关键词搜索，供客户端搜索命令使用。 */
    public List<BilibiliSearchItem> search(String keyword, int page, Path cookiesFile, String proxy) throws Exception {
        return search(keyword, page, cookiesFile, "", proxy);
    }

    /** 带服务端凭证的搜索。 */
    public List<BilibiliSearchItem> search(String keyword, int page, Path cookiesFile, String serverCookie,
                                           String proxy) throws Exception {
        CookieSource cookies = new CookieSource(cookiesFile, serverCookie);
        HttpClient client = client(proxy);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("search_type", "video");
        params.put("keyword", keyword);
        params.put("page", Integer.toString(Math.max(1, page)));
        JsonObject root = root(client, "https://api.bilibili.com/x/web-interface/wbi/search/type?"
                + signedQuery(params, cookies), cookies);
        JsonObject data = root.getAsJsonObject("data");
        JsonArray result = data == null ? null : data.getAsJsonArray("result");
        if (result == null) {
            return List.of();
        }
        List<BilibiliSearchItem> items = new ArrayList<>();
        for (JsonElement element : result) {
            if (items.size() >= 8 || !element.isJsonObject()) {
                break;
            }
            JsonObject item = element.getAsJsonObject();
            String bvid = optString(item, "bvid");
            if (bvid.isBlank()) {
                continue;
            }
            String title = optString(item, "title").replaceAll("</?em[^>]*>", "");
            String author = optString(item, "author");
            long duration = parseDuration(optString(item, "duration"));
            items.add(new BilibiliSearchItem(bvid, title, author, duration));
        }
        return List.copyOf(items);
    }

    private static long parseDuration(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        String[] parts = text.split(":");
        try {
            if (parts.length == 2) {
                return (Long.parseLong(parts[0]) * 60L + Long.parseLong(parts[1])) * 1000L;
            }
            if (parts.length == 3) {
                return (Long.parseLong(parts[0]) * 3600L + Long.parseLong(parts[1]) * 60L + Long.parseLong(parts[2])) * 1000L;
            }
        } catch (NumberFormatException ignored) {
        }
        return 0L;
    }

    @Override
    public ResolvedMedia resolve(String url, Path cookiesFile, int maxHeight, String proxy) throws Exception {
        return resolve(url, cookiesFile, "", maxHeight, proxy);
    }

    /** 带服务端凭证的解析。服务端 Cookie 与本地 cookies.txt 合并，同名以服务端为准。 */
    public ResolvedMedia resolve(String url, Path cookiesFile, String serverCookie, int maxHeight,
                                 String proxy) throws Exception {
        CookieSource cookies = new CookieSource(cookiesFile, serverCookie);
        HttpClient client = client(proxy);
        URI uri = URI.create(resolveShortLink(client, url));
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!(host.equals("bilibili.com") || host.endsWith(".bilibili.com"))) {
            throw new IOException("B 站短链跳转到了非 B 站域名");
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        Matcher bv = BV.matcher(path);
        Matcher av = AV.matcher(path);
        Matcher ep = EP.matcher(path);
        Matcher season = SEASON.matcher(path);
        Matcher live = LIVE.matcher(path);
        int page = queryInt(uri.getRawQuery(), "p", 1);
        if (host.startsWith("live.") && live.find()) {
            return resolveLive(client, Long.parseLong(live.group(1)), cookies);
        }
        if (bv.find()) {
            return resolveVod(client, bv.group(1), null, page, cookies, maxHeight);
        }
        if (av.find()) {
            return resolveVod(client, null, Long.parseLong(av.group(1)), page, cookies, maxHeight);
        }
        if (ep.find()) {
            return resolveBangumi(client, Long.parseLong(ep.group(1)), null, cookies, maxHeight);
        }
        if (season.find()) {
            return resolveBangumi(client, null, Long.parseLong(season.group(1)), cookies, maxHeight);
        }
        throw new IOException("无法识别的 B 站链接");
    }

    private ResolvedMedia resolveVod(HttpClient client, String bvid, Long avid, int page,
                                     CookieSource cookies, int maxHeight) throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        if (bvid != null) {
            query.put("bvid", bvid);
        } else {
            query.put("aid", String.valueOf(avid));
        }
        JsonObject data = data(client, "https://api.bilibili.com/x/web-interface/view?" + plainQuery(query), cookies);
        String resolvedBvid = optString(data, "bvid");
        if (resolvedBvid.isBlank()) {
            resolvedBvid = bvid;
        }
        JsonArray pages = data.getAsJsonArray("pages");
        long cid;
        if (page <= 1 || pages == null || pages.isEmpty()) {
            cid = optLong(data, "cid", 0L);
        } else {
            int index = Math.min(page - 1, pages.size() - 1);
            JsonObject pageData = pages.get(index).getAsJsonObject();
            cid = optLong(pageData, "cid", 0L);
        }
        if (cid <= 0L) {
            throw new IOException("B 站视频缺少 cid");
        }
        Map<String, String> playParams = new LinkedHashMap<>();
        playParams.put("bvid", resolvedBvid);
        playParams.put("cid", Long.toString(cid));
        playParams.put("qn", "127");
        playParams.put("fnval", "4048");
        playParams.put("fnver", "0");
        playParams.put("fourk", "1");
        playParams.put("platform", "pc");
        playParams.put("high_quality", "1");
        JsonObject play = data(client, "https://api.bilibili.com/x/player/playurl?" + signedQuery(playParams, cookies), cookies);
        String title = optString(data, "title");
        long duration = optLong(data, "duration", 0L) * 1000L;
        String referer = "https://www.bilibili.com/video/" + resolvedBvid;
        ResolvedMedia media = parsePlay(play, qualityTarget(maxHeight), referer, title, duration, false);
        if (media == null) {
            throw new IOException("B 站没有可播放的 DASH/直链");
        }
        return media;
    }

    private ResolvedMedia resolveBangumi(HttpClient client, Long epId, Long seasonId,
                                         CookieSource cookies, int maxHeight) throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        if (epId != null) {
            query.put("ep_id", String.valueOf(epId));
        } else {
            query.put("season_id", String.valueOf(seasonId));
        }
        JsonObject root = root(client, "https://api.bilibili.com/pgc/view/web/season?" + plainQuery(query), cookies);
        JsonObject result = root.has("result") ? root.getAsJsonObject("result") : null;
        if (result == null) {
            throw new IOException("B 站番剧信息不可用");
        }
        JsonArray episodes = result.getAsJsonArray("episodes");
        if (episodes == null || episodes.isEmpty()) {
            throw new IOException("B 站番剧没有剧集");
        }
        JsonObject episode = null;
        for (JsonElement element : episodes) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject candidate = element.getAsJsonObject();
            if (epId == null || optLong(candidate, "ep_id", -1L) == epId) {
                episode = candidate;
                break;
            }
        }
        if (episode == null) {
            throw new IOException("找不到指定的 B 站剧集");
        }
        long resolvedEp = optLong(episode, "ep_id", 0L);
        long cid = optLong(episode, "cid", 0L);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ep_id", Long.toString(resolvedEp));
        params.put("cid", Long.toString(cid));
        params.put("fnval", "12240");
        params.put("fourk", "1");
        params.put("qn", "127");
        JsonObject rootPlay = root(client, "https://api.bilibili.com/pgc/player/web/v2/playurl?"
                + signedQuery(params, cookies), cookies);
        JsonObject playResult = rootPlay.has("result") ? rootPlay.getAsJsonObject("result") : null;
        JsonObject videoInfo = playResult == null ? null : playResult.getAsJsonObject("video_info");
        String title = optString(result, "title");
        String episodeTitle = optString(episode, "long_title");
        if (!episodeTitle.isBlank()) {
            title = title.isBlank() ? episodeTitle : title + " · " + episodeTitle;
        }
        long duration = optLong(episode, "duration", 0L);
        ResolvedMedia media = parsePlay(videoInfo, qualityTarget(maxHeight),
                "https://www.bilibili.com/bangumi/play/ep" + resolvedEp, title, duration, false);
        if (media == null) {
            throw new IOException("B 站番剧没有可播放的流（可能需要大会员 Cookie）");
        }
        return media;
    }

    private ResolvedMedia resolveLive(HttpClient client, long roomId, CookieSource cookies) throws Exception {
        JsonObject info = data(client, "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId, cookies);
        String title = optString(info, "title");
        if (optInt(info, "live_status", 0) != 1) {
            throw new IOException("B 站直播间当前未开播");
        }
        long stamp = System.currentTimeMillis();
        List<String> candidates = new ArrayList<>();
        String selected = "";
        // 直播地址由 CDN 的 hot_cache 分发，偶尔会把已经轮换掉的流会话吐回来，
        // 所以带一个时间戳穿透缓存，并且拿到候选后逐个探活，全死就再问一次。
        for (int attempt = 0; attempt < 2 && selected.isBlank(); attempt++) {
            JsonObject play = data(client, "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
                    + "?room_id=" + roomId + "&protocol=0,1&format=0,1,2&codec=0,1&qn=10000"
                    + "&platform=web&ptype=8&dolby=5&panorama=1&_=" + (stamp + attempt), cookies);
            candidates.clear();
            collectLiveCandidates(play, stamp, candidates);
            if (candidates.isEmpty()) {
                continue;
            }
            selected = firstPlayable(client, candidates);
        }
        if (selected.isBlank()) {
            throw new IOException(candidates.isEmpty()
                    ? "B 站直播没有可用的 HTTP 流"
                    : "B 站直播流地址已失效（" + candidates.size() + " 个候选均无响应）");
        }
        Map<String, String> headers = Map.of(
                "Referer", "https://live.bilibili.com/" + roomId,
                "User-Agent", UA,
                "Origin", "https://live.bilibili.com");
        return new ResolvedMedia(selected, "", headers, Map.of(), 0L, title, true);
    }

    /** 收集直播候选地址：覆盖全部 stream / format / codec / url_info，跳过已过期的地址。 */
    private static void collectLiveCandidates(JsonObject play, long nowMillis, List<String> output) {
        JsonObject playurl = optObject(optObject(play, "playurl_info"), "playurl");
        JsonArray streams = playurl == null ? null : playurl.getAsJsonArray("stream");
        if (streams == null) {
            return;
        }
        long nowSeconds = nowMillis / 1000L;
        for (JsonElement streamElement : streams) {
            if (!streamElement.isJsonObject()) {
                continue;
            }
            JsonArray formats = streamElement.getAsJsonObject().getAsJsonArray("format");
            if (formats == null) {
                continue;
            }
            for (JsonElement formatElement : formats) {
                if (!formatElement.isJsonObject()) {
                    continue;
                }
                JsonArray codecs = formatElement.getAsJsonObject().getAsJsonArray("codec");
                if (codecs == null) {
                    continue;
                }
                for (JsonElement codecElement : codecs) {
                    if (!codecElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject codec = codecElement.getAsJsonObject();
                    String base = optString(codec, "base_url");
                    JsonArray urlInfo = codec.getAsJsonArray("url_info");
                    if (base.isBlank() || urlInfo == null) {
                        continue;
                    }
                    for (JsonElement entryElement : urlInfo) {
                        if (!entryElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject entry = entryElement.getAsJsonObject();
                        String host = optString(entry, "host");
                        if (host.isBlank()) {
                            continue;
                        }
                        String url = host + base + optString(entry, "extra");
                        if (output.contains(url) || liveUrlExpired(url, nowSeconds)) {
                            continue;
                        }
                        output.add(url);
                    }
                }
            }
        }
    }

    /** 地址里自带的 expires 早于当前时间就直接丢弃。 */
    private static boolean liveUrlExpired(String url, long nowSeconds) {
        Matcher matcher = Pattern.compile("[?&]expires=(\\d+)").matcher(url);
        if (!matcher.find()) {
            return false;
        }
        try {
            return Long.parseLong(matcher.group(1)) <= nowSeconds;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * 并发探活，返回第一个可用的地址，最慢 {@link #PROBE_TIMEOUT} 一轮。
     *
     * <p>串行探测在「第一个 host 卡住」时会白等好几秒，玩家看到的是长时间黑屏，
     * 所以这里同时问前几个候选，谁先应声就用谁。HEAD 只问状态码，不落流数据；
     * CDN 不理 HEAD 返回 405 视为存活。</p>
     */
    private static String firstPlayable(HttpClient client, List<String> candidates) {
        if (candidates.isEmpty()) {
            return "";
        }
        List<String> probeList = new ArrayList<>(candidates.subList(0, Math.min(MAX_PROBES, candidates.size())));
        ExecutorService pool = Executors.newFixedThreadPool(probeList.size(), runnable -> {
            Thread thread = new Thread(runnable, "mikuvideo-live-probe");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<String>> futures = new ArrayList<>(probeList.size());
        try {
            for (String candidate : probeList) {
                futures.add(pool.submit(() -> liveProbe(client, candidate) ? candidate : ""));
            }
            boolean uncertain = false;
            String fallback = "";
            for (int index = 0; index < futures.size(); index++) {
                try {
                    String value = futures.get(index).get();
                    if (!value.isBlank()) {
                        return value;
                    }
                } catch (Exception ignored) {
                    uncertain = true;
                    if (fallback.isEmpty()) {
                        fallback = probeList.get(index);
                    }
                }
            }
            // 全部明确 404 时返回空串，让上层换时间戳重问一次；网络异常则交回第一个候选给播放层试
            return uncertain ? fallback : "";
        } finally {
            pool.shutdownNow();
        }
    }

    /** HEAD 只取状态码；200/206/405 视为可用。 */
    private static boolean liveProbe(HttpClient client, String url) {
        try {
            HttpRequest probe = HttpRequest.newBuilder(URI.create(url))
                    .timeout(PROBE_TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .header("Origin", "https://live.bilibili.com")
                    .build();
            int code = client.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode();
            return code == 200 || code == 206 || code == 405;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 解析 playurl 的 dash/durl。 */
    private ResolvedMedia parsePlay(JsonObject data, int targetQuality, String referer,
                                    String title, long durationMillis, boolean live) {
        if (data == null) {
            return null;
        }
        Map<String, String> headers = Map.of(
                "Referer", referer,
                "User-Agent", UA,
                "Origin", "https://www.bilibili.com");
        JsonObject dash = optObject(data, "dash");
        if (dash != null) {
            JsonArray videos = dash.getAsJsonArray("video");
            JsonObject bestVideo = null;
            int bestScore = Integer.MIN_VALUE;
            if (videos != null) {
                for (JsonElement element : videos) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject video = element.getAsJsonObject();
                    String url = mediaUrl(video);
                    if (url.isBlank()) {
                        continue;
                    }
                    int quality = optInt(video, "id", 0);
                    int rank = qualityRank(quality);
                    if (rank < 0 || rank > qualityRank(targetQuality)) {
                        continue;
                    }
                    int codec = optInt(video, "codecid", 0);
                    int codecScore = codec == 7 ? 300 : codec == 12 ? 200 : codec == 13 ? 100 : 0;
                    int score = rank * 10_000 + codecScore + (int) Math.min(999, optLong(video, "bandwidth", 0L) / 1_000_000L);
                    if (score > bestScore) {
                        bestScore = score;
                        bestVideo = video;
                    }
                }
            }
            if (bestVideo == null && videos != null) {
                // 没有不超过目标档位的流时，退而取最低档，避免直接失败。
                int lowestRank = Integer.MAX_VALUE;
                for (JsonElement element : videos) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject video = element.getAsJsonObject();
                    if (mediaUrl(video).isBlank()) {
                        continue;
                    }
                    int rank = qualityRank(optInt(video, "id", 0));
                    if (rank >= 0 && rank < lowestRank) {
                        lowestRank = rank;
                        bestVideo = video;
                    }
                }
            }
            if (bestVideo != null) {
                String videoUrl = mediaUrl(bestVideo);
                String audioUrl = "";
                Map<String, String> audioHeaders = Map.of();
                JsonArray audios = dash.getAsJsonArray("audio");
                JsonObject bestAudio = null;
                long bestBandwidth = -1L;
                if (audios != null) {
                    for (JsonElement element : audios) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        JsonObject audio = element.getAsJsonObject();
                        if (mediaUrl(audio).isBlank()) {
                            continue;
                        }
                        long bandwidth = optLong(audio, "bandwidth", 0L);
                        if (bandwidth > bestBandwidth) {
                            bestBandwidth = bandwidth;
                            bestAudio = audio;
                        }
                    }
                }
                if (bestAudio != null) {
                    audioUrl = mediaUrl(bestAudio);
                    audioHeaders = headers;
                }
                return new ResolvedMedia(videoUrl, audioUrl, headers, audioHeaders, durationMillis, title, live);
            }
        }
        // 低清/旧视频只有 progressive（durl）。
        JsonArray durl = data.getAsJsonArray("durl");
        if (durl == null) {
            durl = data.getAsJsonArray("durls");
        }
        if (durl != null && !durl.isEmpty() && durl.get(0).isJsonObject()) {
            String url = optString(durl.get(0).getAsJsonObject(), "url");
            if (!url.isBlank()) {
                return new ResolvedMedia(url, "", headers, Map.of(), durationMillis, title, live);
            }
        }
        return null;
    }

    private String mediaUrl(JsonObject object) {
        String url = optString(object, "baseUrl");
        if (url.isBlank()) {
            url = optString(object, "base_url");
        }
        if (!url.isBlank()) {
            return normalizeCdn(url);
        }
        JsonArray backup = object.getAsJsonArray("backupUrl");
        if (backup == null) {
            backup = object.getAsJsonArray("backup_url");
        }
        if (backup != null) {
            for (JsonElement element : backup) {
                if (element.isJsonPrimitive() && !element.getAsString().isBlank()) {
                    return normalizeCdn(element.getAsString());
                }
            }
        }
        return "";
    }

    private static String normalizeCdn(String url) {
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        return url;
    }

    private static int qualityTarget(int maxHeight) {
        if (maxHeight >= 2160) return 127;
        if (maxHeight >= 1440) return 120;
        if (maxHeight >= 1080) return 80;
        if (maxHeight >= 720) return 64;
        if (maxHeight >= 480) return 32;
        return 16;
    }

    private static int qualityRank(int qn) {
        for (int index = 0; index < QUALITY_RANK.length; index++) {
            if (QUALITY_RANK[index] == qn) {
                return index;
            }
        }
        return -1;
    }

    private String resolveShortLink(HttpClient client, String url) throws Exception {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!(host.equals("b23.tv") || host.endsWith(".b23.tv"))) {
            return url;
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Accept", "text/html,*/*")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            // 只关心最终地址。
        }
        URI location = response.uri();
        if (location == null || location.getHost() == null) {
            throw new IOException("B 站短链解析失败");
        }
        String finalHost = location.getHost().toLowerCase(Locale.ROOT);
        if (!(finalHost.equals("bilibili.com") || finalHost.endsWith(".bilibili.com"))) {
            throw new IOException("B 站短链跳转到了非 B 站域名");
        }
        return location.toString();
    }

    private HttpClient client(String proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy != null && !proxy.isBlank()) {
            try {
                String value = proxy.strip();
                if (value.contains("://")) {
                    value = value.substring(value.indexOf("://") + 3);
                }
                value = value.substring(0, value.contains("/") ? value.indexOf('/') : value.length());
                String host = value;
                int port = 80;
                int colon = value.lastIndexOf(':');
                if (colon > 0) {
                    host = value.substring(0, colon);
                    port = Integer.parseInt(value.substring(colon + 1));
                }
                builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
            } catch (RuntimeException ignored) {
            }
        }
        return builder.build();
    }

    private JsonObject data(HttpClient client, String url, CookieSource cookies) throws Exception {
        return requireData(root(client, url, cookies), "B 站 API");
    }

    private JsonObject root(HttpClient client, String url, CookieSource cookies) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .header("Accept", "application/json, text/plain, */*")
                .GET();
        String cookie = cookieHeader(cookies);
        if (!cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("B 站 HTTP " + response.statusCode());
        }
        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonObject()) {
            throw new IOException("B 站返回了非 JSON 数据");
        }
        return parsed.getAsJsonObject();
    }

    private JsonObject requireData(JsonObject root, String api) throws IOException {
        int code = optInt(root, "code", 0);
        if (code != 0) {
            throw new IOException(api + " 返回错误 " + code + "：" + optString(root, "message"));
        }
        JsonObject data = root.getAsJsonObject("data");
        if (data == null) {
            throw new IOException(api + " 没有 data");
        }
        return data;
    }

    private String signedQuery(Map<String, String> params, CookieSource cookies) {
        Map<String, String> all = new LinkedHashMap<>(params);
        all.put("wts", Long.toString(System.currentTimeMillis() / 1000L));
        List<String> keys = new ArrayList<>(all.keySet());
        keys.sort(Comparator.naturalOrder());
        StringBuilder base = new StringBuilder();
        for (String key : keys) {
            if (base.length() > 0) {
                base.append('&');
            }
            base.append(urlEncode(key)).append('=').append(urlEncode(filterValue(all.get(key))));
        }
        WbiKeys wbi = wbiKeys(cookies);
        if (wbi == null) {
            return base.toString();
        }
        String wRid = md5(base + mixinKey(wbi.imgKey(), wbi.subKey()));
        return base + "&w_rid=" + wRid;
    }

    private WbiKeys wbiKeys(CookieSource cookies) {
        long now = System.currentTimeMillis();
        WbiKeys cached = wbiKeys;
        if (cached != null && now - wbiFetchedAt < 20L * 60L * 60L * 1000L) {
            return cached;
        }
        try {
            HttpClient client = client("");
            // 未登录时 nav 会返回 code=-101，但 data.wbi_img 仍然存在，不能按业务错误丢弃。
            JsonObject root = root(client, "https://api.bilibili.com/x/web-interface/nav", cookies);
            JsonObject data = root.getAsJsonObject("data");
            JsonObject wbi = data == null ? null : data.getAsJsonObject("wbi_img");
            if (wbi == null) {
                return cached;
            }
            String img = keySegment(optString(wbi, "img_url"));
            String sub = keySegment(optString(wbi, "sub_url"));
            if (sub.isBlank()) {
                sub = keySegment(optString(wbi, "sub_key_url"));
            }
            if (img.isBlank() || sub.isBlank()) {
                return cached;
            }
            WbiKeys keys = new WbiKeys(img, sub);
            wbiKeys = keys;
            wbiFetchedAt = now;
            return keys;
        } catch (Exception error) {
            return cached;
        }
    }

    private static String keySegment(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String value = url.substring(url.lastIndexOf('/') + 1);
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(0, dot) : value;
    }

    private static String mixinKey(String imgKey, String subKey) {
        String raw = imgKey + subKey;
        StringBuilder builder = new StringBuilder(MIXIN_TABLE_LENGTH);
        for (int index : MIXIN_KEY_ENC_TAB) {
            if (index < raw.length()) {
                builder.append(raw.charAt(index));
            }
        }
        return builder.toString();
    }

    private static String filterValue(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c != '!' && c != '\'' && c != '(' && c != ')' && c != '*') {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static String plainQuery(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        return builder.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception error) {
            return "";
        }
    }

    /** 本地 cookies.txt + 服务端下发凭证；同名时服务端优先。 */
    private static String cookieHeader(CookieSource source) {
        if (source == null) {
            return "";
        }
        String fromFile = cookieHeader(source.file());
        String extra = source.header() == null ? "" : source.header().trim();
        if (extra.isEmpty()) {
            return fromFile;
        }
        if (fromFile.isEmpty()) {
            return extra;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        parseCookieHeader(fromFile, merged);
        parseCookieHeader(extra, merged);
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private static void parseCookieHeader(String header, Map<String, String> output) {
        for (String part : header.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                output.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
            }
        }
    }

    private static String cookieHeader(Path cookiesFile) {
        if (cookiesFile == null || !Files.isRegularFile(cookiesFile)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try {
            for (String line : Files.readAllLines(cookiesFile, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\t");
                if (parts.length < 7) {
                    continue;
                }
                String domain = parts[0].toLowerCase(Locale.ROOT);
                if (!domain.contains("bilibili.com")) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append("; ");
                }
                builder.append(parts[5]).append('=').append(parts[6]);
            }
        } catch (IOException ignored) {
            return "";
        }
        return builder.toString();
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static int queryInt(String query, String key, int fallback) {
        if (query == null) {
            return fallback;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                try {
                    return Integer.parseInt(part.substring(eq + 1));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static JsonObject optObject(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String optString(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static long optLong(JsonObject object, String key, long fallback) {
        JsonElement element = object == null ? null : object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static int optInt(JsonObject object, String key, int fallback) {
        JsonElement element = object == null ? null : object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private record CookieSource(Path file, String header) {
    }

    private record WbiKeys(String imgKey, String subKey) {
    }
}