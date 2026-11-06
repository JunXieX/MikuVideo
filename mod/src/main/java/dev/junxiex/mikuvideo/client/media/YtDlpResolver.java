package dev.junxiex.mikuvideo.client.media;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.junxiex.mikuvideo.client.media.resolve.MediaResolver;
import dev.junxiex.mikuvideo.client.media.resolve.ResolvedMedia;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用 yt-dlp 把网页地址解析成 DASH/直链地址。
 *
 * <p>只解析 URL，不下载媒体本体；B 站等站点的音视频会作为两个独立地址返回，
 * 由解码引擎分别拉起视频/音频进程。解析在后台线程执行，超时 30 秒。</p>
 *
 * @author JunXieX
 */
public final class YtDlpResolver implements MediaResolver {
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 16 * 1024;
    private static final long TIMEOUT_SECONDS = 30L;

    private final YtDlpLocator locator;

    public YtDlpResolver(YtDlpLocator locator) {
        this.locator = locator;
    }

    public boolean available() {
        return locator.locate() != null;
    }

    public String statusText() {
        return locator.statusText();
    }

    @Override
    public ResolvedMedia resolve(String url, Path cookiesFile, int maxHeight, String proxy) throws Exception {
        Path executable = locator.locate();
        if (executable == null) {
            throw new IOException("未找到 yt-dlp，无法解析网页视频");
        }
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("--no-playlist");
        command.add("--no-warnings");
        command.add("--no-progress");
        command.add("--socket-timeout");
        command.add("30");
        command.add("--retries");
        command.add("3");
        if (cookiesFile != null && Files.isRegularFile(cookiesFile)) {
            command.add("--cookies");
            command.add(cookiesFile.toAbsolutePath().toString());
        }
        if (proxy != null && !proxy.isBlank()) {
            command.add("--proxy");
            command.add(proxy.strip());
        }
        command.add("-J");
        command.add(url);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread outReader = daemon("ytdlp-out", () -> copy(process.getInputStream(), stdout, MAX_JSON_BYTES));
        Thread errReader = daemon("ytdlp-err", () -> copy(process.getErrorStream(), stderr, MAX_STDERR_BYTES));
        outReader.start();
        errReader.start();
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("yt-dlp 解析超时");
        }
        join(outReader);
        join(errReader);
        String json = stdout.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0 || json.isBlank()) {
            String error = stderr.toString(StandardCharsets.UTF_8).strip();
            if (error.length() > 500) {
                error = error.substring(error.length() - 500);
            }
            throw new IOException("yt-dlp 解析失败: " + (error.isBlank() ? "退出码 " + process.exitValue() : error));
        }
        return parse(json, maxHeight);
    }

    private static ResolvedMedia parse(String json, int maxHeight) throws IOException {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new IOException("yt-dlp 返回了非对象 JSON");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (root.has("_type") && "playlist".equals(root.get("_type").getAsString())) {
            JsonArray entries = root.getAsJsonArray("entries");
            if (entries == null || entries.isEmpty() || !entries.get(0).isJsonObject()) {
                throw new IOException("播放列表为空");
            }
            root = entries.get(0).getAsJsonObject();
        }
        String title = string(root, "title");
        long durationMillis = (long) Math.round(number(root, "duration", 0.0) * 1000.0);
        boolean live = bool(root, "is_live");
        JsonArray formats = root.getAsJsonArray("formats");
        if (formats == null || formats.isEmpty()) {
            String direct = string(root, "url");
            if (!direct.isBlank()) {
                return new ResolvedMedia(direct, "", headers(root), Map.of(), durationMillis, title, live);
            }
            throw new IOException("没有可播放的格式");
        }

        JsonObject bestVideo = null;
        JsonObject bestCombined = null;
        JsonObject bestAudio = null;
        double videoScore = Double.NEGATIVE_INFINITY;
        double combinedScore = Double.NEGATIVE_INFINITY;
        double audioScore = Double.NEGATIVE_INFINITY;

        for (JsonElement element : formats) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject format = element.getAsJsonObject();
            String url = string(format, "url");
            if (url.isBlank()) {
                continue;
            }
            String vcodec = string(format, "vcodec");
            String acodec = string(format, "acodec");
            boolean hasVideo = !vcodec.isBlank() && !"none".equals(vcodec);
            boolean hasAudio = !acodec.isBlank() && !"none".equals(acodec);
            if (!hasVideo && !hasAudio) {
                continue;
            }
            int height = (int) number(format, "height", 0.0);
            if (hasVideo && maxHeight > 0 && height > maxHeight) {
                continue;
            }
            double tbr = number(format, "tbr", 0.0);
            String ext = string(format, "ext");
            boolean mp4 = "mp4".equalsIgnoreCase(ext) || "m4a".equalsIgnoreCase(ext);
            boolean avc = vcodec.startsWith("avc");
            if (hasVideo && hasAudio) {
                double score = height * 1000.0 + (avc ? 500.0 : 0.0) + (mp4 ? 100.0 : 0.0) + tbr;
                if (score > combinedScore) {
                    combinedScore = score;
                    bestCombined = format;
                }
            } else if (hasVideo) {
                double score = height * 1000.0 + (avc ? 500.0 : 0.0) + (mp4 ? 100.0 : 0.0) + tbr;
                if (score > videoScore) {
                    videoScore = score;
                    bestVideo = format;
                }
            } else {
                double score = tbr + (mp4 ? 1000.0 : 0.0);
                if (score > audioScore) {
                    audioScore = score;
                    bestAudio = format;
                }
            }
        }

        JsonObject video = bestVideo != null ? bestVideo : bestCombined;
        if (video == null) {
            throw new IOException("没有找到不超过 " + maxHeight + "p 的视频格式");
        }
        String videoUrl = string(video, "url");
        Map<String, String> videoHeaders = headers(video);
        String audioUrl = "";
        Map<String, String> audioHeaders = Map.of();
        if (bestVideo != null && bestAudio != null) {
            audioUrl = string(bestAudio, "url");
            audioHeaders = headers(bestAudio);
        }
        if (title.isBlank()) {
            title = string(root, "id");
        }
        return new ResolvedMedia(videoUrl, audioUrl, videoHeaders, audioHeaders, durationMillis, title, live);
    }

    private static Map<String, String> headers(JsonObject format) {
        JsonElement element = format.get("http_headers");
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return result;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static double number(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && !element.isJsonNull() && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private static void copy(InputStream input, ByteArrayOutputStream output, int limit) {
        byte[] buffer = new byte[8192];
        try (input) {
            int read;
            while (output.size() < limit && (read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
        }
    }

    private static Thread daemon(String name, Runnable task) {
        Thread thread = new Thread(task, "mikuvideo-" + name);
        thread.setDaemon(true);
        return thread;
    }

    private static void join(Thread thread) {
        try {
            thread.join(2_000L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
