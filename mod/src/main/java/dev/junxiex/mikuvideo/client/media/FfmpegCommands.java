package dev.junxiex.mikuvideo.client.media;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 组装 ffmpeg 命令行。
 *
 * <p>参数顺序按 ffmpeg 的"输入选项在 -i 之前、输出选项在 -i 之后"规则固定下来：
 * 协议白名单、请求头、超时、断线重连、输入侧 seek 属于输入选项；码率、滤镜、封装格式属于输出选项。
 * 任何改动都会直接影响解码行为，集中在这里便于对照。</p>
 *
 * @author JunXieX
 */
final class FfmpegCommands {

    /** 允许的协议白名单：本地文件、HTTP(S)、TLS 与内联数据。 */
    static final String PROTOCOL_WHITELIST = "file,http,https,tcp,tls,crypto,data,httpproxy";

    /** 读写超时（微秒），15 秒。 */
    static final long RW_TIMEOUT_MICROS = 15_000_000L;

    /** 只有网络输入才追加的断线重连参数。 */
    private static final String[] RECONNECT_ARGS = {
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "5"
    };

    /** 请求未指定 User-Agent 时补的默认值，部分 CDN 没有 UA 会直接拒绝。 */
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MikuVideo/1.0";

    private FfmpegCommands() {
    }

    /**
     * 视频解码命令：输出固定 {@code width×height×4} 字节的 RGBA 裸帧。
     *
     * <p>滤镜链负责等比缩放、黑边补齐并去掉非方形像素比，
     * 这样上层拿到的每一帧都是相同尺寸、像素中心对齐的纹理源。</p>
     */
    static List<String> video(Path ffmpeg, MediaRequest request, int width, int height, int fps, long startMillis) {
        List<String> args = new ArrayList<>(32);
        args.add(ffmpeg.toString());
        addGlobal(args);
        addInputNetwork(args, request.url(), request.headers());
        addSeek(args, startMillis);
        args.add("-i");
        args.add(request.url());
        args.add("-map");
        args.add("0:v:0");
        args.add("-an");
        args.add("-sn");
        args.add("-dn");
        args.add("-vf");
        args.add(scaleFilter(width, height));
        args.add("-r");
        args.add(Integer.toString(fps));
        args.add("-f");
        args.add("rawvideo");
        args.add("-pix_fmt");
        args.add("rgba");
        args.add("-");
        return args;
    }

    /** 音频解码命令：48kHz / 16bit / 双声道小端裸 PCM，缺少音轨时按可选流处理。 */
    static List<String> audio(Path ffmpeg, MediaRequest request, long startMillis) {
        List<String> args = new ArrayList<>(32);
        args.add(ffmpeg.toString());
        addGlobal(args);
        addInputNetwork(args, request.effectiveAudioUrl(), request.audioHeaders());
        addSeek(args, startMillis);
        args.add("-i");
        args.add(request.effectiveAudioUrl());
        args.add("-map");
        args.add("0:a:0?");
        args.add("-vn");
        args.add("-sn");
        args.add("-dn");
        args.add("-f");
        args.add("s16le");
        args.add("-ar");
        args.add(Integer.toString(FfmpegMediaSession.AUDIO_RATE));
        args.add("-ac");
        args.add(Integer.toString(FfmpegMediaSession.AUDIO_CHANNELS));
        args.add("-");
        return args;
    }

    /** 等比缩放 + 黑边补齐 + 方形像素。 */
    static String scaleFilter(int width, int height) {
        return "scale=" + width + ":" + height
                + ":force_original_aspect_ratio=decrease:flags=bilinear,"
                + "pad=" + width + ":" + height + ":(ow-iw)/2:(oh-ih)/2:color=black,setsar=1";
    }

    /** 是否为需要断线重连参数的远程输入。 */
    static boolean isRemote(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static void addGlobal(List<String> args) {
        args.add("-hide_banner");
        args.add("-loglevel");
        args.add("error");
        args.add("-nostats");
        args.add("-nostdin");
        args.add("-protocol_whitelist");
        args.add(PROTOCOL_WHITELIST);
    }

    private static void addInputNetwork(List<String> args, String url, Map<String, String> headers) {
        // -headers 与 -reconnect 都是 http/https 协议选项：给 file 输入加上会直接报 "Option not found"
        if (isRemote(url)) {
            String headerBlock = headerBlock(headers);
            if (!headerBlock.isEmpty()) {
                args.add("-headers");
                args.add(headerBlock);
            }
        }
        args.add("-rw_timeout");
        args.add(Long.toString(RW_TIMEOUT_MICROS));
        if (isRemote(url)) {
            for (String arg : RECONNECT_ARGS) {
                args.add(arg);
            }
        }
    }

    private static void addSeek(List<String> args, long startMillis) {
        if (startMillis <= 0L) {
            return;
        }
        args.add("-ss");
        args.add(String.format(Locale.ROOT, "%.3f", startMillis / 1000.0));
    }

    /** 把请求头拼成 ffmpeg 的 {@code -headers} 取值：每条以 CRLF 结尾。 */
    static String headerBlock(Map<String, String> headers) {
        StringBuilder builder = new StringBuilder();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String name = sanitize(entry.getKey());
                String value = sanitize(entry.getValue());
                if (name.isEmpty()) {
                    continue;
                }
                builder.append(name).append(": ").append(value).append("\r\n");
            }
        }
        if (!builder.toString().toLowerCase(Locale.ROOT).contains("user-agent:")) {
            builder.insert(0, "User-Agent: " + DEFAULT_USER_AGENT + "\r\n");
        }
        return builder.toString();
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r", "").replace("\n", "").strip();
    }
}