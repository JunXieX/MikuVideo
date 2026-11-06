package dev.junxiex.mikuvideo.client.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 定位 yt-dlp：显式配置 → mod 内置 → 系统 PATH。
 *
 * <p>定位过程只会在解析线程执行，不阻塞客户端主线程。</p>
 *
 * @author JunXieX
 */
public final class YtDlpLocator {
    private final Path gameDir;
    private volatile String configuredPath = "";
    private volatile Path cached;
    private volatile String version = "";
    private volatile boolean attempted;

    public YtDlpLocator(Path gameDir) {
        this.gameDir = gameDir == null ? Path.of(".").toAbsolutePath().normalize() : gameDir.toAbsolutePath().normalize();
    }

    public void setPath(String path) {
        this.configuredPath = path == null ? "" : path.strip();
        this.cached = null;
        this.version = "";
        this.attempted = false;
    }

    public synchronized Path locate() {
        if (attempted) {
            return cached;
        }
        attempted = true;
        List<Path> candidates = new ArrayList<>();
        if (!configuredPath.isBlank()) {
            Path configured = Path.of(configuredPath);
            candidates.add(Files.isDirectory(configured)
                    ? configured.resolve(isWindows() ? "yt-dlp.exe" : "yt-dlp") : configured);
        }
        Path bundled = BundledYtDlp.ensure(gameDir.resolve("mikuvideo").resolve("ytdlp"));
        if (bundled != null) {
            candidates.add(bundled);
        }
        for (Path candidate : candidates) {
            if (probe(candidate)) {
                cached = candidate;
                return cached;
            }
        }
        for (String name : new String[]{"yt-dlp", "yt-dlp.exe"}) {
            if (probe(Path.of(name))) {
                cached = Path.of(name);
                return cached;
            }
        }
        cached = null;
        return null;
    }

    public String version() {
        Path path = locate();
        return path == null ? "" : version;
    }

    public String statusText() {
        Path path = locate();
        if (path == null) {
            return "未找到 yt-dlp（网页视频不可解析；直链视频不受影响）";
        }
        return "yt-dlp " + (version.isBlank() ? "版本未知" : version) + "  " + path;
    }

    private boolean probe(Path candidate) {
        if (candidate == null) {
            return false;
        }
        if (candidate.isAbsolute() && !Files.isRegularFile(candidate)) {
            return false;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(candidate.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).strip();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() != 0) {
                return false;
            }
            version = output.lines().findFirst().orElse("").strip();
            return true;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
