package dev.junxiex.mikuvideo.util;

import dev.junxiex.mikuvideo.config.Settings;
import dev.junxiex.mikuvideo.protocol.Protocol;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 媒体地址校验。
 *
 * <p>服务端只做字面量判断，绝不对目标域名做 DNS 解析或任何网络请求，
 * 因此这里的“内网”判定只覆盖字面 IP 与本机/局域网域名后缀；
 * 客户端拿到地址后还会做一次同样的校验。</p>
 *
 * @author JunXieX
 */
public final class Urls {
    private Urls() {
    }

    /** 校验结果。失败时 {@code message} 是可读原因，成功时 {@code headers} 可直接发给客户端。 */
    public record Checked(boolean ok, String message, String url, Map<String, String> headers) {
        public static Checked fail(String message) {
            return new Checked(false, message == null ? "链接不合法" : message, "", Map.of());
        }

        public static Checked pass(String url, Map<String, String> headers) {
            return new Checked(true, "", url, headers == null ? Map.of() : Map.copyOf(headers));
        }
    }

    /**
     * 校验播放地址与可选 Referer。
     *
     * @param rawUrl   原始地址
     * @param referer  可选 Referer，空表示不发送
     * @param media    媒体相关配置
     */
    public static Checked validate(String rawUrl, String referer, Settings.Media media) {
        if (media == null) {
            return Checked.fail("媒体配置缺失");
        }
        if (rawUrl == null || rawUrl.isBlank()) {
            return Checked.fail("链接不能为空");
        }
        String url = rawUrl.trim();
        int maxLength = Math.min(media.maxUrlLength(), Protocol.MAX_URL);
        if (Text.utf8Length(url) > maxLength) {
            return Checked.fail("链接过长，上限 " + maxLength + " 字节");
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                return Checked.fail("链接里不能包含空白或控制字符");
            }
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Checked.fail("链接格式不合法");
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            return Checked.fail("链接必须带 http:// 或 https:// 前缀");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!media.allowedSchemes().contains(scheme)) {
            return Checked.fail("只允许这些协议: " + String.join(", ", media.allowedSchemes()));
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Checked.fail("链接缺少主机名");
        }
        String normalizedHost = normalizeHost(host);
        if (!media.allowPrivateHosts() && isPrivateHost(normalizedHost)) {
            return Checked.fail("不允许指向本机或内网地址");
        }
        if (isBlocked(normalizedHost, media.blockedHosts())) {
            return Checked.fail("该主机已被服务器屏蔽");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (referer != null && !referer.isBlank()) {
            if (!isHeaderAllowed("Referer", media.allowedHeaderNames())) {
                return Checked.fail("服务器未允许 Referer 请求头");
            }
            headers.put("Referer", sanitizeHeaderValue(referer));
        }
        if (headers.size() > Math.min(media.maxHeaders(), Protocol.MAX_HEADER_COUNT)) {
            return Checked.fail("请求头数量超过上限");
        }
        return Checked.pass(url, headers);
    }

    /** 去掉 IPv6 字面量外层的中括号并转小写。 */
    public static String normalizeHost(String host) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 字面量判定本机 / 内网地址，不做任何 DNS 解析。 */
    public static boolean isPrivateHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String value = normalizeHost(host);
        if (value.equals("localhost") || value.endsWith(".localhost") || value.endsWith(".local")) {
            return true;
        }
        if (value.contains(":")) {
            byte[] ipv6 = parseIpv6(value);
            return ipv6 != null && isPrivateIpv6(ipv6);
        }
        byte[] ipv4 = parseIpv4(value);
        return ipv4 != null && isPrivateIpv4(ipv4);
    }

    /** 黑名单匹配：精确或 {@code *.example.com} 通配。 */
    public static boolean isBlocked(String host, List<String> patterns) {
        if (host == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        String value = normalizeHost(host);
        for (String pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            String rule = pattern.trim().toLowerCase(Locale.ROOT);
            if (rule.isEmpty()) {
                continue;
            }
            if (rule.startsWith("*.")) {
                String suffix = rule.substring(1);
                if (value.endsWith(suffix) && value.length() > suffix.length()) {
                    return true;
                }
            } else if (value.equals(rule)) {
                return true;
            }
        }
        return false;
    }

    /** 请求头名是否在白名单内（大小写不敏感）。 */
    public static boolean isHeaderAllowed(String name, List<String> allowed) {
        if (name == null || allowed == null) {
            return false;
        }
        for (String candidate : allowed) {
            if (candidate != null && candidate.trim().equalsIgnoreCase(name.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 去掉 CR/LF 并截断到协议上限，防止头注入。 */
    public static String sanitizeHeaderValue(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\r", "").replace("\n", "").trim();
        return Text.trimBytes(cleaned, Protocol.MAX_HEADER_VALUE);
    }

    private static boolean isPrivateIpv4(byte[] ip) {
        int a = ip[0] & 0xFF;
        int b = ip[1] & 0xFF;
        if (a == 0 || a == 10 || a == 127) {
            return true;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return true;
        }
        if (a == 192 && b == 168) {
            return true;
        }
        return a == 169 && b == 254;
    }

    private static boolean isPrivateIpv6(byte[] ip) {
        boolean allZero = true;
        for (int i = 0; i < 15; i++) {
            if (ip[i] != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero && ip[15] <= 1) {
            // :: 与 ::1
            return true;
        }
        if ((ip[0] & 0xFE) == 0xFC) {
            // fc00::/7 唯一本地地址
            return true;
        }
        if ((ip[0] & 0xFF) == 0xFE && (ip[1] & 0xC0) == 0x80) {
            // fe80::/10 链路本地
            return true;
        }
        boolean mapped = true;
        for (int i = 0; i < 10; i++) {
            if (ip[i] != 0) {
                mapped = false;
                break;
            }
        }
        if (mapped && (ip[10] & 0xFF) == 0xFF && (ip[11] & 0xFF) == 0xFF) {
            byte[] embedded = {ip[12], ip[13], ip[14], ip[15]};
            return isPrivateIpv4(embedded);
        }
        return false;
    }

    private static byte[] parseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            out[i] = (byte) value;
        }
        return out;
    }

    private static byte[] parseIpv6(String host) {
        String value = host;
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        if (value.isEmpty()) {
            return null;
        }
        int doubleColon = value.indexOf("::");
        if (doubleColon >= 0 && value.indexOf("::", doubleColon + 1) >= 0) {
            return null;
        }
        String head = doubleColon >= 0 ? value.substring(0, doubleColon) : value;
        String tail = doubleColon >= 0 ? value.substring(doubleColon + 2) : "";
        List<Integer> headGroups = parseGroups(head);
        List<Integer> tailGroups = parseGroups(tail);
        if (headGroups == null || tailGroups == null) {
            return null;
        }
        int total = headGroups.size() + tailGroups.size();
        if (doubleColon < 0) {
            if (total != 8) {
                return null;
            }
        } else if (total > 7) {
            return null;
        }
        byte[] out = new byte[16];
        int index = 0;
        for (int group : headGroups) {
            out[index++] = (byte) (group >> 8);
            out[index++] = (byte) (group & 0xFF);
        }
        index += (8 - total) * 2;
        for (int group : tailGroups) {
            out[index++] = (byte) (group >> 8);
            out[index++] = (byte) (group & 0xFF);
        }
        return out;
    }

    private static List<Integer> parseGroups(String part) {
        List<Integer> groups = new ArrayList<>(8);
        if (part.isEmpty()) {
            return groups;
        }
        String[] tokens = part.split(":", -1);
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                return null;
            }
            if (token.indexOf('.') >= 0) {
                if (i != tokens.length - 1) {
                    return null;
                }
                byte[] ipv4 = parseIpv4(token);
                if (ipv4 == null) {
                    return null;
                }
                groups.add(((ipv4[0] & 0xFF) << 8) | (ipv4[1] & 0xFF));
                groups.add(((ipv4[2] & 0xFF) << 8) | (ipv4[3] & 0xFF));
                continue;
            }
            if (token.length() > 4) {
                return null;
            }
            int value = 0;
            for (int j = 0; j < token.length(); j++) {
                char c = token.charAt(j);
                int digit = Character.digit(c, 16);
                if (digit < 0) {
                    return null;
                }
                value = value * 16 + digit;
            }
            groups.add(value);
        }
        return groups;
    }
}
