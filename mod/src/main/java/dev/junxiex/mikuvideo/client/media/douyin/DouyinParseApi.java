package dev.junxiex.mikuvideo.client.media.douyin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 抖音视频解析接口客户端。
 *
 * <p>抖音网页版详情接口从 2026 年起要求 secsdk 的 {@code x-secsdk-web-signature}，
 * 该签名依赖页面安全 SDK 的会话密钥，纯 HTTP 无法复现。这里改走可配置的解析服务：
 * 默认使用公开接口，服主或玩家可以换成自建服务，留空则关闭抖音视频解析。</p>
 *
 * <p>接口只需返回 JSON，视频地址从 {@code data.item.url}、{@code data.url}、
 * {@code data.video.url} 等常见字段里取第一个可用值。</p>
 *
 * @author JunXieX
 */
final class DouyinParseApi {
    static final String DEFAULT_ENDPOINT = "https://api.xingzhige.com/API/douyin/?url={url}";
    private static final int MAX_BYTES = 1024 * 1024;

    private DouyinParseApi() {
    }

    static ResolvedMedia resolve(String shareUrl, String template, String proxy) throws Exception {
        if (template == null || template.isBlank()) {
            throw new IOException("抖音视频解析接口未配置");
        }
        String endpoint = endpoint(template, shareUrl);
        HttpClient client = buildClient(proxy);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", DouyinWebClient.USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Referer", "https://www.douyin.com/")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("抖音解析接口 HTTP " + response.statusCode());
                }
                byte[] data = input.readNBytes(MAX_BYTES + 1);
                if (data.length > MAX_BYTES) {
                    throw new IOException("抖音解析接口响应过大");
                }
                body = new String(data, StandardCharsets.UTF_8);
            }
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new IOException("抖音解析接口返回了非 JSON 数据");
            }
            JsonObject root = parsed.getAsJsonObject();
            int code = integer(root, "code", 0);
            if (code != 0 && code != 200) {
                String message = string(root, "msg");
                throw new IOException("抖音解析失败" + (message.isBlank() ? "" : "：" + message));
            }
            JsonObject data = object(root, "data");
            JsonObject item = object(data, "item");
            JsonObject video = object(data, "video");
            String url = firstUrl(
                    string(item, "url"),
                    string(item, "play_addr"),
                    string(data, "url"),
                    string(video, "url"),
                    string(data, "video_url"),
                    string(root, "url"));
            if (url.isBlank()) {
                throw new IOException("抖音解析接口没有返回视频地址");
            }
            URI media = URI.create(url);
            String scheme = media.getScheme() == null ? "" : media.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IOException("抖音解析接口返回了不支持的地址");
            }
            String title = firstText(
                    string(item, "title"),
                    string(data, "title"),
                    string(data, "desc"),
                    string(item, "desc"),
                    "抖音视频");
            if (title.length() > 120) {
                title = title.substring(0, 120);
            }
            JsonObject author = object(data, "author");
            String authorName = string(author, "name");
            if (!authorName.isBlank()) {
                title = title + " · " + authorName;
            }
            long duration = Math.round(doubleValue(item, "duration", 0.0D) * 1000.0D);
            Map<String, String> headers = Map.of(
                    "User-Agent", DouyinWebClient.USER_AGENT,
                    "Referer", "https://www.douyin.com/");
            return new ResolvedMedia(url, "", headers, Map.of(), duration, title, false);
        } finally {
            client.shutdownNow();
        }
    }

    private static String endpoint(String template, String shareUrl) {
        String encoded = URLEncoder.encode(shareUrl, StandardCharsets.UTF_8);
        if (template.contains("{url}")) {
            return template.replace("{url}", encoded);
        }
        return template + (template.contains("?") ? "&" : "?") + "url=" + encoded;
    }

    private static String firstUrl(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.replace('\n', ' ').trim();
            }
        }
        return "抖音视频";
    }

    private static HttpClient buildClient(String proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
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

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name)) {
            return null;
        }
        JsonElement value = parent.get(name);
        return value == null || value.isJsonNull() || !value.isJsonObject() ? null : value.getAsJsonObject();
    }

    private static String string(JsonObject parent, String name) {
        if (parent == null || !parent.has(name)) {
            return "";
        }
        JsonElement value = parent.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static int integer(JsonObject parent, String name, int fallback) {
        if (parent == null || !parent.has(name)) {
            return fallback;
        }
        try {
            return parent.get(name).getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static double doubleValue(JsonObject parent, String name, double fallback) {
        if (parent == null || !parent.has(name)) {
            return fallback;
        }
        try {
            return parent.get(name).getAsDouble();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
