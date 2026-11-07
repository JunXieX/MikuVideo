package dev.junxiex.mikuvideo.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * 文本工具：统一 MiniMessage 解析与消息拼接。
 *
 * <p>配置里的文本一律按 MiniMessage 解析，解析失败时退化为纯文本，
 * 避免一条写错的配置把整个命令反馈打崩。</p>
 *
 * @author JunXieX
 */
public final class Text {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Text() {
    }

    /** 把 MiniMessage 字符串解析成组件，非法标签退化为纯文本。 */
    public static Component parse(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI.deserialize(input);
        } catch (RuntimeException e) {
            return Component.text(input);
        }
    }

    /** 解析前缀并拼接消息正文。 */
    public static Component prefixed(String prefix, String body) {
        return parse(prefix).append(parse(body));
    }

    /** 解析前缀并拼接已有组件。 */
    public static Component prefixed(String prefix, Component body) {
        return parse(prefix).append(body == null ? Component.empty() : body);
    }



    /** UTF-8 编码后的字节长度。 */
    public static int utf8Length(String value) {
        if (value == null) {
            return 0;
        }
        int bytes = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            if (codePoint < 0x80) {
                bytes += 1;
            } else if (codePoint < 0x800) {
                bytes += 2;
            } else if (codePoint < 0x10000) {
                bytes += 3;
            } else {
                bytes += 4;
            }
            i += Character.charCount(codePoint);
        }
        return bytes;
    }

    /**
     * 按 UTF-8 字节数截断，且不会截断半个字符。
     *
     * <p>协议里的所有字符串上限都是字节数，中文一个字占 3 字节，
     * 因此不能按 {@link String#length()} 判断。</p>
     */
    public static String trimBytes(String value, int maxBytes) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (utf8Length(value) <= maxBytes) {
            return value;
        }
        int bytes = 0;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int size = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + size > maxBytes) {
                break;
            }
            bytes += size;
            index += Character.charCount(codePoint);
        }
        return value.substring(0, index);
    }

    /** 把控制字符（含换行）替换成空格并按字节截断，用于写日志或提示客户端传来的文本。 */
    public static String safe(String value, int maxBytes) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            builder.append(c < 0x20 || c == 0x7F ? ' ' : c);
        }
        return trimBytes(builder.toString(), maxBytes);
    }

    /** 截断字符串到指定长度（按字符计）。 */
    public static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, max));
    }

    /** 把毫秒格式化成 mm:ss 或 h:mm:ss。 */
    public static String duration(long millis) {
        if (millis <= 0L) {
            return "--:--";
        }
        long totalSeconds = millis / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    /** 保留两位小数的坐标 / 尺寸文本。 */
    public static String number(double value) {
        return String.format("%.2f", value);
    }
}
