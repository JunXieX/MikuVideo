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
 * 从 mod jar 内置资源解压 FFmpeg。
 *
 * <p>资源布局：{@code /mikuvideo/ffmpeg/<platform>.zip}，zip 内只有 ffmpeg 可执行文件。
 * 首次播放时解压到 {@code <gameDir>/mikuvideo/ffmpeg/bundled-1/<platform>/}，
 * 之后直接复用；解压失败返回 {@code null}，上层回退到系统 PATH / 手动路径。
 * 纯 JDK 实现，不依赖 Minecraft。</p>
 *
 * @author JunXieX
 */
public final class BundledFfmpeg {
    /** 内置包版本；内置 FFmpeg 升级时递增，触发重新解压。 */
    private static final String BUNDLE_VERSION = "1";
    private static final String RESOURCE_ROOT = "/mikuvideo/ffmpeg/";
    private static final long MIN_BINARY_BYTES = 1024L;

    private BundledFfmpeg() {
    }

    /**
     * 确保当前平台的内置 FFmpeg 已解压并返回可执行文件路径。
     *
     * @param bundleDir 通常是 {@code <gameDir>/mikuvideo/ffmpeg}
     * @return 可执行文件；当前平台没有内置包或解压失败时返回 {@code null}
     */
    public static Path ensure(Path bundleDir) {
        String platform = platformKey();
        if (platform == null || bundleDir == null) {
            return null;
        }
        Path directory = bundleDir.resolve("bundled-" + BUNDLE_VERSION).resolve(platform);
        Path binary = directory.resolve(isWindows() ? "ffmpeg.exe" : "ffmpeg");
        try {
            if (Files.isRegularFile(binary) && Files.size(binary) > MIN_BINARY_BYTES) {
                makeExecutable(binary);
                return binary;
            }
            String resource = RESOURCE_ROOT + platform + ".zip";
            try (InputStream input = BundledFfmpeg.class.getResourceAsStream(resource)) {
                if (input == null) {
                    return null;
                }
                Files.createDirectories(directory);
                Path temporary = Files.createTempFile(directory, "ffmpeg", ".tmp");
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
                cleanupOldVersions(bundleDir, directory);
                return binary;
            }
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    /** 当前平台标识；未内置的平台返回 {@code null}。 */
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
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(binary, permissions);
            } catch (IOException | UnsupportedOperationException ignored) {
            }
        }
    }

    /** 清理旧的内置版本目录，避免升级后堆积 GB 级二进制。 */
    private static void cleanupOldVersions(Path bundleDir, Path current) {
        try (var stream = Files.list(bundleDir)) {
            for (Path candidate : stream.toList()) {
                if (Files.isDirectory(candidate)
                        && candidate.getFileName().toString().startsWith("bundled-")
                        && !candidate.equals(current.getParent())) {
                    deleteRecursively(candidate);
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void deleteRecursively(Path path) {
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
