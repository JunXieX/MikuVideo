package dev.junxiex.mikuvideo.client.media.douyin;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 抖音网页端访问：分享短链展开与直播间首屏抓取。
 *
 * @author JunXieX
 */
final class DouyinWebClient implements AutoCloseable {
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final int MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Set<String> TRUSTED_HOSTS = Set.of(
            "douyin.com", "www.douyin.com", "iesdouyin.com", "www.iesdouyin.com",
            "live.douyin.com", "v.douyin.com");

    private final HttpClient client;
    private final SecureRandom random = new SecureRandom();

    DouyinWebClient(String proxy) {
        this.client = buildClient(proxy);
    }

    /** 跟随分享短链，逐跳校验域名。 */
    URI followShortLink(String url) throws IOException {
        URI current;
        try {
            current = URI.create(url);
        } catch (RuntimeException error) {
            throw new IOException("抖音链接格式不合法");
        }
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (DouyinResolver.classify(current) != null
                    && !"v.douyin.com".equalsIgnoreCase(current.getHost())) {
                return current;
            }
            if (!trusted(current)) {
                throw new IOException("抖音短链跳转到了非抖音域名");
            }
            String location = redirect(current, true);
            if (location == null) {
                location = redirect(current, false);
            }
            if (location == null) {
                throw new IOException("抖音短链没有返回跳转地址");
            }
            current = current.resolve(location);
            if (!trusted(current)) {
                throw new IOException("抖音短链跳转到了非抖音域名");
            }
        }
        throw new IOException("抖音短链跳转次数过多");
    }

    /** 直播间首屏 HTML。 */
    String livePage(String roomId) throws IOException {
        String nonce = randomHex(21);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Cookie", "__ac_nonce=" + nonce);
        byte[] body = read(URI.create("https://live.douyin.com/" + roomId), headers, Duration.ofSeconds(20));
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

    private String redirect(URI uri, boolean head) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "text/html,application/xhtml+xml,*/*");
        if (!head) {
            headers.put("Range", "bytes=0-0");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            if (head) {
                builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            } else {
                builder.GET();
            }
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    return response.headers().firstValue("Location").orElse(null);
                }
                return null;
            }
        } catch (Exception error) {
            return null;
        }
    }

    private byte[] read(URI uri, Map<String, String> headers, Duration timeout) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("抖音 HTTP " + response.statusCode());
                }
                byte[] data = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
                if (data.length > MAX_DOCUMENT_BYTES) {
                    throw new IOException("抖音响应超过大小上限");
                }
                return data;
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("抖音请求失败：" + error.getMessage(), error);
        }
    }

    private String randomHex(int length) {
        char[] value = new char[length];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < value.length; index++) {
            value[index] = alphabet[random.nextInt(alphabet.length)];
        }
        return new String(value);
    }

    private static boolean trusted(URI uri) {
        String host = uri.getHost();
        return host != null && TRUSTED_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT));
    }

    private static HttpClient buildClient(String proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1);
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
}
