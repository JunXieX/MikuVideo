package dev.junxiex.mikuvideo.client.util;

import java.net.URI;
import java.util.Locale;

/** 客户端侧的地址安全校验，避免把观众的内网当作视频源。 */
public final class ClientUrls {
    private ClientUrls() {
    }

    public static boolean isAllowed(String rawUrl, boolean allowPrivateHosts) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (Exception error) {
            return false;
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        if (allowPrivateHosts) {
            return true;
        }
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.equals("localhost") || value.endsWith(".localhost") || value.endsWith(".local")) {
            return false;
        }
        if (value.contains(":")) {
            // 只有整段就是 v4-mapped（::ffff:a.b.c.d）时才按内嵌 IPv4 判定；
            // fe80::ffff:8.8.8.8 这类是链路本地地址，交给 IPv6 规则拒绝。
            boolean mapped = value.startsWith("::ffff:") || value.startsWith("0:0:0:0:0:ffff:");
            if (mapped) {
                String tail = value.substring(value.lastIndexOf("ffff:") + 5);
                return tail.contains(".") && isPublicIpv4(tail);
            }
            return isPublicIpv6(value);
        }
        return isPublicIpv4(value);
    }

    /** 数字/十六进制变体（2130706433、0x7f.0.0.1、127.1）交给系统解析后会落到内网，一律拒绝。 */
    private static boolean isNumericHost(String host) {
        if (host.isEmpty()) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean digit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!digit && c != '.' && c != 'x' && c != 'X') {
                return false;
            }
        }
        return true;
    }

    private static boolean isPublicIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return !isNumericHost(host);
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return !isNumericHost(host);
            }
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException error) {
                return !isNumericHost(host);
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return !isNumericHost(host);
            }
        }
        int a = octets[0];
        int b = octets[1];
        if (a == 0 || a == 10 || a == 127) {
            return false;
        }
        if (a == 100 && b >= 64 && b <= 127) {
            return false;
        }
        if (a == 169 && b == 254) {
            return false;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return false;
        }
        if (a == 192 && b == 168) {
            return false;
        }
        if (a == 198 && (b == 18 || b == 19)) {
            return false;
        }
        return true;
    }

    private static boolean isPublicIpv6(String host) {
        String value = host;
        if (value.equals("::1") || value.equals("::")) {
            return false;
        }
        if (value.startsWith("fc") || value.startsWith("fd")) {
            return false;
        }
        if (value.startsWith("fe8") || value.startsWith("fe9")
                || value.startsWith("fea") || value.startsWith("feb")) {
            return false;
        }
        return true;
    }
}