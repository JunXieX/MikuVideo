package dev.junxiex.mikuvideo.client.media.douyin;

import dev.junxiex.mikuvideo.client.media.resolve.MediaResolver;
import dev.junxiex.mikuvideo.client.media.resolve.ResolvedMedia;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音解析：直播间走原生页面，视频走可配置解析接口。
 *
 * <p>抖音视频详情接口自 2026 年起要求 secsdk 页面签名，纯 HTTP 无法稳定复现，
 * 因此视频解析交给 {@link DouyinParseApi}（可换成自建服务）；直播页仍然是原生抓取。</p>
 *
 * @author JunXieX
 */
public final class DouyinResolver implements MediaResolver {
    private static final Pattern VIDEO_PATH =
            Pattern.compile("^/(?:video|share/video|share/forward|note)/(\\d{15,22})(?:/.*)?$");
    private static final Pattern LIVE_PATH = Pattern.compile("^/(\\d{3,20})(?:/.*)?$");
    private static final Pattern MODAL_ID = Pattern.compile("(?:^|&)modal_id=(\\d{15,22})(?:&|$)");

    /** 默认解析接口，可在客户端配置里替换。 */
    public static final String DEFAULT_API = DouyinParseApi.DEFAULT_ENDPOINT;

    private volatile String apiTemplate = DEFAULT_API;

    /** 设置视频解析接口；传空表示关闭抖音视频解析。 */
    public void setApiTemplate(String template) {
        this.apiTemplate = template == null ? "" : template.trim();
    }

    public String apiTemplate() {
        return apiTemplate;
    }

    public boolean supports(String url) {
        String host = hostOf(url);
        return host != null && (host.equals("douyin.com") || host.endsWith(".douyin.com")
                || host.equals("iesdouyin.com") || host.endsWith(".iesdouyin.com"));
    }

    @Override
    public ResolvedMedia resolve(String url, Path cookiesFile, int maxHeight, String proxy) throws Exception {
        try (DouyinWebClient client = new DouyinWebClient(proxy)) {
            URI finalUri = client.followShortLink(url);
            Target target = classify(finalUri);
            if (target == null) {
                throw new IOException("无法识别的抖音链接，请粘贴视频或直播间地址");
            }
            return target.live ? live(client, target) : video(target, proxy);
        }
    }

    private ResolvedMedia video(Target target, String proxy) throws Exception {
        String canonical = "https://www.douyin.com/video/" + target.id;
        return DouyinParseApi.resolve(canonical, apiTemplate, proxy);
    }

    private ResolvedMedia live(DouyinWebClient client, Target target) throws Exception {
        String html = client.livePage(target.id);
        DouyinLivePageParser.LiveInfo info = DouyinLivePageParser.parse(html);
        if (info == null) {
            throw new IOException("抖音直播页没有返回房间状态");
        }
        if (info.status() != 2) {
            throw new IOException("抖音直播间当前未开播");
        }
        String stream = !info.hls().isBlank() ? info.hls() : info.flv();
        if (stream.isBlank()) {
            throw new IOException("抖音直播间没有可用的拉流地址");
        }
        String title = info.title().isBlank() ? "抖音直播 " + target.id : info.title();
        String referer = "https://live.douyin.com/" + target.id;
        Map<String, String> headers = Map.of(
                "User-Agent", DouyinWebClient.USER_AGENT,
                "Referer", referer,
                "Origin", "https://live.douyin.com");
        return new ResolvedMedia(stream, "", headers, Map.of(), 0L, title, true);
    }

    /** 识别链接类型；不是抖音地址时返回 {@code null}。 */
    static Target classify(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return null;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "/" : uri.getPath();
        if (host.equals("live.douyin.com")) {
            Matcher live = LIVE_PATH.matcher(path);
            return live.matches() ? new Target(true, live.group(1)) : null;
        }
        boolean videoHost = host.equals("douyin.com") || host.endsWith(".douyin.com")
                || host.equals("iesdouyin.com") || host.endsWith(".iesdouyin.com");
        if (!videoHost) {
            return null;
        }
        Matcher video = VIDEO_PATH.matcher(path);
        if (video.matches()) {
            return new Target(false, video.group(1));
        }
        String query = uri.getRawQuery();
        if (query != null) {
            Matcher modal = MODAL_ID.matcher(query);
            if (modal.find()) {
                return new Target(false, modal.group(1));
            }
        }
        return null;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException error) {
            return null;
        }
    }

    record Target(boolean live, String id) {
    }
}
