package dev.junxiex.mikuvideo.client.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 可选的 ffmpeg 下载器。
 *
 * <p>默认不自动下载：只有显式调用才会走网络。下载目标固定为
 * {@code <gameDir>/mikuvideo/ffmpeg/<平台>-<版本>/ffmpeg[.exe]}，
 * 这样多版本并存时老目录可以被识别为陈旧构建并清理。</p>
 *
 * <p>Windows x64 走 zip（{@link java.util.zip} 解压，只取 {@code bin/ffmpeg.exe}）；
 * Linux x64/arm64 走 tar.xz 并调用系统 {@code tar -xJf}；macOS 不提供自动下载。</p>
 *
 * <p>下载先写 {@code .part}，落盘校验大小后原子改名，避免半截文件被当成完整包。</p>
 *
 * @author JunXieX
 */
public final class FfmpegDownloader {

    /** 进度回调，在下载线程调用。{@code total} 未知时为 -1。 */
    @FunctionalInterface
    public interface ProgressSink {
        void onProgress(long done, long total);
    }

    /** 目标 ffmpeg 版本。 */
    public static final String VERSION = "9.0";

    private static final String RELEASE_BASE =
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest";
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final long PROGRESS_STEP_BYTES = 1L << 20;
    private static final long TAR_TIMEOUT_MS = 180_000L;

    private final Path root;
    private final ProgressSink progress;
    /** 下载串行化必须跨实例生效：不同实例写的是同一个 .part 文件。 */
    private static final Object DOWNLOAD_LOCK = new Object();

    public FfmpegDownloader(Path root) {
        this(root, (done, total) -> {
        });
    }

    public FfmpegDownloader(Path root, ProgressSink progress) {
        this.root = (root == null ? FfmpegLocator.defaultGameDir().resolve("mikuvideo").resolve("ffmpeg") : root)
                .toAbsolutePath().normalize();
        this.progress = progress == null ? (done, total) -> {
        } : progress;
    }


    /**
     * 当前平台标识：{@code windows-x64}、{@code linux-x64}、{@code linux-aarch64}；
     * 不支持自动下载的平台返回 {@code null}。
     */
    public static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        boolean x64 = arch.contains("x86_64") || arch.contains("amd64") || arch.contains("x64");
        if (os.contains("win")) {
            return x64 ? "windows-x64" : null;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return null;
        }
        if (os.contains("linux")) {
            if (arm) {
                return "linux-aarch64";
            }
            return x64 ? "linux-x64" : null;
        }
        return null;
    }

    /** 当前平台是否支持自动下载。 */
    public static boolean isSupported() {
        return platformKey() != null;
    }

    /** 不支持自动下载时的提示文案。 */
    public static String unsupportedReason() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "macOS 不提供自动下载：请执行 brew install ffmpeg，或在设置中手动指定 ffmpeg 路径";
        }
        return "当前平台（" + System.getProperty("os.name") + " / " + System.getProperty("os.arch")
                + "）没有预编译包：请手动安装 ffmpeg 并在设置中指定路径";
    }

    /** 该平台对应的下载地址；不支持时返回 {@code null}。 */
    static String assetUrl(String platformKey) {
        if (platformKey == null) {
            return null;
        }
        switch (platformKey) {
            case "windows-x64":
                return RELEASE_BASE + "/ffmpeg-n" + VERSION + "-latest-win64-gpl-" + VERSION + ".zip";
            case "linux-x64":
                return RELEASE_BASE + "/ffmpeg-n" + VERSION + "-latest-linux64-gpl-" + VERSION + ".tar.xz";
            case "linux-aarch64":
                return RELEASE_BASE + "/ffmpeg-n" + VERSION + "-latest-linuxarm64-gpl-" + VERSION + ".tar.xz";
            default:
                return null;
        }
    }

    /** 目标可执行文件路径；平台不支持时为 {@code null}。 */
    public Path targetBinary() {
        String key = platformKey();
        if (key == null) {
            return null;
        }
        return root.resolve(key + "-" + VERSION).resolve(FfmpegLocator.executableName());
    }

    /**
     * 确保本地有可用二进制：已存在且非空直接返回，否则下载并解压。
     *
     * <p>失败时把异常抛给调用方，由上层决定是否回退到系统 ffmpeg 并给出提示。</p>
     */
    public Path downloadIfMissing() throws IOException {
        Path binary = targetBinary();
        if (binary != null && isUsable(binary)) {
            return binary;
        }
        return download();
    }

    /** 强制下载（已存在也复用）。 */
    public Path download() throws IOException {
        synchronized (DOWNLOAD_LOCK) {
            String key = platformKey();
            if (key == null) {
                throw new IOException(unsupportedReason());
            }
            Path binary = targetBinary();
            if (isUsable(binary)) {
                return binary;
            }
            String url = assetUrl(key);
            if (url == null) {
                throw new IOException(unsupportedReason());
            }
            Path dir = binary.getParent();
            Files.createDirectories(dir);

            boolean zip = url.endsWith(".zip");
            Path archive = root.resolve(key + "-" + VERSION + (zip ? ".zip" : ".tar.xz"));
            Path partial = root.resolve(archive.getFileName() + ".part");
            Files.deleteIfExists(partial);
            try {
                fetch(url, partial);
                moveAtomically(partial, archive);
                if (zip) {
                    extractZip(archive, binary);
                } else {
                    extractTar(archive, dir, binary);
                }
                if (!isUsable(binary)) {
                    throw new IOException("解压后没有得到可用的 ffmpeg：" + binary);
                }
                binary.toFile().setExecutable(true, false);
                return binary;
            } finally {
                Files.deleteIfExists(partial);
                Files.deleteIfExists(archive);
            }
        }
    }

    private void fetch(String url, Path partial) throws IOException {
        Files.createDirectories(partial.getParent());
        progress.onProgress(0L, -1L);
        URI target = URI.create(url);
        URLConnection connection = target.toURL().openConnection(detectProxy());
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "MikuVideo/1.0");
        connection.setRequestProperty("Accept", "*/*");
        if (connection instanceof HttpURLConnection http) {
            http.setInstanceFollowRedirects(true);
            int code = http.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载失败：HTTP " + code + "（" + url + "）");
            }
        }
        long total = connection.getContentLengthLong();
        long done = 0L;
        long lastReport = 0L;
        try (InputStream input = connection.getInputStream();
             OutputStream output = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                done += read;
                if (done - lastReport >= PROGRESS_STEP_BYTES) {
                    lastReport = done;
                    progress.onProgress(done, total);
                }
            }
        }
        progress.onProgress(done, total > 0 ? total : done);
        if (done <= 0L) {
            throw new IOException("下载内容为空：" + url);
        }
    }

    /**
     * 解析可用代理。
     *
     * <p>玩家环境常见直连被墙、必须走本机代理的情况，而 {@link URLConnection} 默认既不读系统代理
     * 也不读 {@code HTTPS_PROXY}，会表现为"连接建立后一直收不到数据"。这里按
     * JVM 属性 → 环境变量 → 平台默认选择器 的顺序取第一个可用代理。</p>
     */
    static Proxy detectProxy() {
        String host = System.getProperty("https.proxyHost");
        String port = System.getProperty("https.proxyPort");
        if (host == null || host.isBlank()) {
            host = System.getProperty("http.proxyHost");
            port = System.getProperty("http.proxyPort");
        }
        if (host != null && !host.isBlank()) {
            return httpProxy(host, port);
        }
        for (String key : new String[]{"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"}) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                Proxy parsed = parseProxyUrl(value);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        ProxySelector selector = ProxySelector.getDefault();
        if (selector != null) {
            try {
                var proxies = selector.select(URI.create("https://github.com"));
                if (proxies != null && !proxies.isEmpty() && proxies.get(0).type() != Proxy.Type.DIRECT) {
                    return proxies.get(0);
                }
            } catch (RuntimeException ignored) {
                // 选择器异常时按直连处理
            }
        }
        return Proxy.NO_PROXY;
    }

    private static Proxy parseProxyUrl(String value) {
        String spec = value.strip();
        int schemeEnd = spec.indexOf("://");
        String hostPort = schemeEnd >= 0 ? spec.substring(schemeEnd + 3) : spec;
        int slash = hostPort.indexOf('/');
        if (slash >= 0) {
            hostPort = hostPort.substring(0, slash);
        }
        int at = hostPort.lastIndexOf('@');
        if (at >= 0) {
            hostPort = hostPort.substring(at + 1);
        }
        String host = hostPort;
        String port = null;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0) {
            host = hostPort.substring(0, colon);
            port = hostPort.substring(colon + 1);
        }
        if (host.isBlank()) {
            return null;
        }
        return httpProxy(host, port);
    }

    private static Proxy httpProxy(String host, String port) {
        int number;
        try {
            number = port == null || port.isBlank() ? 80 : Integer.parseInt(port.strip());
        } catch (NumberFormatException e) {
            number = 80;
        }
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host.strip(), number));
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 只从 zip 里取 {@code bin/ffmpeg.exe}，不落地整包内容。 */
    private static void extractZip(Path archive, Path binary) throws IOException {
        Files.createDirectories(binary.getParent());
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (!name.toLowerCase(Locale.ROOT).endsWith("bin/ffmpeg.exe")) {
                    zip.closeEntry();
                    continue;
                }
                try (OutputStream output = Files.newOutputStream(binary)) {
                    zip.transferTo(output);
                }
                zip.closeEntry();
                return;
            }
        }
        throw new IOException("压缩包里没有 bin/ffmpeg.exe：" + archive);
    }

    /** 用系统 tar 解压 tar.xz，再把二进制挪到固定位置并清理残留。 */
    private static void extractTar(Path archive, Path dir, Path binary) throws IOException {
        Files.createDirectories(dir);
        Process process = new ProcessBuilder("tar", "-xJf", archive.toString(), "-C", dir.toString())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes());
        }
        try {
            if (!process.waitFor(TAR_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("tar 解压超时");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("tar 解压被中断", e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("tar 解压失败（退出码 " + process.exitValue() + "）：" + output.strip());
        }
        Path found = findBinary(dir);
        if (found == null) {
            throw new IOException("解压后没有找到 ffmpeg：" + dir);
        }
        if (!found.equals(binary)) {
            Files.move(found, binary, StandardCopyOption.REPLACE_EXISTING);
        }
        cleanDirectory(dir, binary);
    }

    private static Path findBinary(Path dir) throws IOException {
        List<Path> candidates;
        try (var stream = Files.walk(dir, 4)) {
            candidates = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("ffmpeg"))
                    .toList();
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** 删除解压残留，只保留可执行文件本身。 */
    private static void cleanDirectory(Path dir, Path keep) throws IOException {
        List<Path> entries;
        try (var stream = Files.list(dir)) {
            entries = stream.toList();
        }
        for (Path entry : entries) {
            if (entry.equals(keep)) {
                continue;
            }
            deleteRecursively(entry);
        }
    }

    private static void deleteRecursively(Path path) {
        try (var stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }

    private static boolean isUsable(Path binary) {
        try {
            return binary != null && Files.isRegularFile(binary) && Files.size(binary) > 0L;
        } catch (IOException e) {
            return false;
        }
    }
}
