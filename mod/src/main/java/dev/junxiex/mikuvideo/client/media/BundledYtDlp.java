package dev.junxiex.mikuvideo.client.media;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从 mod jar 解压内置 yt-dlp（网页视频解析器）。
 *
 * <p>资源布局 {@code /mikuvideo/ytdlp/<platform>.zip}，解压到
 * {@code <gameDir>/mikuvideo/ytdlp/bundled-1/<platform>/}。失败返回 {@code null}，
 * 上层回退到系统 PATH 或用户指定路径。</p>
 *
 * @author JunXieX
 */
public final class BundledYtDlp {
    private static final String BUNDLE_VERSION = "1";
    private static final String RESOURCE_ROOT = "/mikuvideo/ytdlp/";
    private static final long MIN_BINARY_BYTES = 1024L;

    private BundledYtDlp() {
    }

    public static Path ensure(Path baseDir) {
        String platform = platformKey();
        if (platform == null || baseDir == null) {
            return null;
        }
        Path directory = baseDir.resolve("bundled-" + BUNDLE_VERSION).resolve(platform);
        Path binary = directory.resolve(isWindows() ? "yt-dlp.exe" : "yt-dlp");
        try {
            if (Files.isRegularFile(binary) && Files.size(binary) > MIN_BINARY_BYTES) {
                makeExecutable(binary);
                return binary;
            }
            try (InputStream input = BundledYtDlp.class.getResourceAsStream(RESOURCE_ROOT + platform + ".zip")) {
                if (input == null) {
                    return null;
                }
                Files.createDirectories(directory);
                Path temporary = Files.createTempFile(directory, "yt-dlp", ".tmp");
                try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
                    ZipEntry entry = zip.getNextEntry();
                    if (entry == null || entry.isDirectory()) {
                        Files.deleteIfExists(temporary);
                        return null;
                    }
                    Files.copy(zip, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.size(temporary) <= MIN_BINARY_BYTES) {
                    Files.deleteIfExists(temporary);
                    return null;
                }
                try {
                    Files.move(temporary, binary, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(temporary, binary, StandardCopyOption.REPLACE_EXISTING);
                }
                makeExecutable(binary);
                return binary;
            }
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    public static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("win")) {
            return x64 ? "windows-x64" : null;
        }
        if (os.contains("linux")) {
            return x64 ? "linux-x64" : null;
        }
        if (os.contains("mac")) {
            return arm64 ? "macos-arm64" : null;
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void makeExecutable(Path binary) {
        try {
            binary.toFile().setExecutable(true, false);
        } catch (RuntimeException ignored) {
        }
        if (!isWindows()) {
            try {
                Set<PosixFilePermission> permissions = EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(binary, permissions);
            } catch (IOException | UnsupportedOperationException ignored) {
            }
        }
    }
}
