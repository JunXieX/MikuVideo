package dev.junxiex.mikuvideo.client.media;

import java.util.Locale;

/**
 * ffmpeg 失败原因归类。
 *
 * <p>ffmpeg 的错误输出是给人看的英文自由文本，上层需要的是可判定的类别与稳定错误码，
 * 因此这里把 stderr 尾部折叠成有限几种情况：网络、404、格式不支持、无视频流、超时、其他。
 * 错误码会随 {@link MediaSession#lastError()} 一起给到上层，便于做重试策略或提示文案。</p>
 *
 * @author JunXieX
 */
public final class FfmpegError {

    /** 没有错误。 */
    public static final int NONE = 0;
    /** 网络不可达、连接被拒绝、TLS 失败等。 */
    public static final int NETWORK = 1;
    /** 资源不存在（404 / 文件不存在）。 */
    public static final int NOT_FOUND = 2;
    /** 容器或编码不支持、数据损坏。 */
    public static final int FORMAT = 3;
    /** 输入里没有视频流。 */
    public static final int NO_VIDEO = 4;
    /** 连接或读取超时。 */
    public static final int TIMEOUT = 5;
    /** 输入里没有音频流。 */
    public static final int NO_AUDIO = 6;
    /** 本机没有可用的 ffmpeg。 */
    public static final int BINARY_MISSING = 7;
    /** 进程本身启动失败或异常退出。 */
    public static final int PROCESS = 8;
    /** 未归类。 */
    public static final int UNKNOWN = 9;

    private static final int TAIL_LIMIT = 400;

    private FfmpegError() {
    }

    /** 错误码的短标签，例如 {@code E2}。 */
    public static String code(int code) {
        return code == NONE ? "" : "E" + code;
    }

    /** 错误码对应的中文说明。 */
    public static String describe(int code) {
        switch (code) {
            case NONE:
                return "正常";
            case NETWORK:
                return "网络连接失败";
            case NOT_FOUND:
                return "资源不存在（404 或文件缺失）";
            case FORMAT:
                return "格式或编码不受支持";
            case NO_VIDEO:
                return "输入中没有视频流";
            case TIMEOUT:
                return "连接或读取超时";
            case NO_AUDIO:
                return "输入中没有音频流";
            case BINARY_MISSING:
                return "未找到 ffmpeg";
            case PROCESS:
                return "解码进程异常";
            default:
                return "未知错误";
        }
    }

    /**
     * 归类视频解码进程的 stderr 尾部。
     *
     * <p>判定顺序从"最具体"到"最笼统"：先看 404，再看超时、网络、格式、无流，最后落到其他。</p>
     */
    public static int classify(String stderr) {
        String text = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return UNKNOWN;
        }
        if (containsAny(text, "404 not found", "http error 404", "no such file or directory", "404")) {
            return NOT_FOUND;
        }
        if (containsAny(text, "timed out", "timeout")) {
            return TIMEOUT;
        }
        if (containsAny(text,
                "connection refused",
                "connection reset",
                "could not resolve",
                "failed to resolve",
                "name or service not known",
                "server returned",
                "http error",
                "403 forbidden",
                "ssl",
                "tls",
                "handshake",
                "i/o error",
                "input/output error",
                "broken pipe",
                "error opening input",
                "immediate exit requested",
                "protocol not found")) {
            return NETWORK;
        }
        if (containsAny(text, "matches no streams", "does not contain any stream", "stream map")
                && text.contains("video")) {
            return NO_VIDEO;
        }
        if (containsAny(text,
                "invalid data found when processing input",
                "moov atom not found",
                "could not find codec parameters",
                "unknown decoder",
                "no decoder",
                "decoder (codec",
                "unsupported codec",
                "error while decoding",
                "invalid argument",
                "common video formats")) {
            return FORMAT;
        }
        if (containsAny(text, "matches no streams", "does not contain any stream")) {
            return NO_VIDEO;
        }
        if (containsAny(text, "conversion failed", "error while opening encoder", "cannot write header")) {
            return PROCESS;
        }
        return UNKNOWN;
    }

    /** 归类音频解码进程的 stderr 尾部；无音频流是正常情况，单独成一类。 */
    public static int classifyAudio(String stderr) {
        String text = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        if (text.contains("does not contain any stream") || text.contains("matches no streams")) {
            return NO_AUDIO;
        }
        return classify(stderr);
    }

    /** 组装 {@code E2 资源不存在（404）：<stderr 尾部>} 形式的一行可读错误。 */
    public static String format(int code, String stderr) {
        StringBuilder builder = new StringBuilder();
        String label = code(code);
        if (!label.isEmpty()) {
            builder.append(label).append(' ');
        }
        builder.append(describe(code));
        String tail = compact(stderr);
        if (!tail.isEmpty()) {
            builder.append("｜").append(tail);
        }
        if (builder.length() > TAIL_LIMIT + 40) {
            return builder.substring(0, TAIL_LIMIT + 40) + "…";
        }
        return builder.toString();
    }

    /** 把多行 stderr 压成一行，方便塞进界面提示。 */
    public static String compact(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        String oneLine = stderr.strip().replace("\r", "").replace("\n", " / ");
        while (oneLine.contains(" /  / ")) {
            oneLine = oneLine.replace(" /  / ", " / ");
        }
        if (oneLine.length() > TAIL_LIMIT) {
            oneLine = oneLine.substring(oneLine.length() - TAIL_LIMIT);
        }
        return oneLine;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
