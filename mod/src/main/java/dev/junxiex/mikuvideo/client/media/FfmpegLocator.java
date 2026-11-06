package dev.junxiex.mikuvideo.client.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 定位可用的 ffmpeg 可执行文件。
 *
 * <p>查找顺序固定，先命中先返回：</p>
 * <ol>
 *   <li>显式配置的路径（设置界面/启动参数指定）；</li>
 *   <li>系统 PATH（Windows 上先问 {@code where}，再直接试 {@code ffmpeg.exe}）；</li>
 *   <li>系统常用安装目录（Linux/macOS）；</li>
 *   <li>游戏目录下的 {@code mikuvideo/ffmpeg/}（含下载器生成的按平台分目录）；</li>
 *   <li>用户下载目录下的 {@code ffmpeg}。</li>
 * </ol>
 *
 * <p>每个候选都会跑一次 {@code ffmpeg -version} 探测：3 秒超时、独立线程排空输出、
 * 失败立即销毁进程。结果缓存，{@link #setPath(String)} 与 {@link #reload()} 会清空缓存。</p>
 *
 * @author JunXieX
 */
public final class FfmpegLocator {

    /** 单次探测的超时时间。 */
    private static final long PROBE_TIMEOUT_MS = 3_000L;
    /** 探测输出最多保留的字节数，防止异常程序刷屏。 */
    private static final int PROBE_MAX_BYTES = 64 * 1024;

    private final Path gameDir;
    private final ReentrantLock scanLock = new ReentrantLock();

    private volatile String explicitPath;
    private volatile long generation;
    private volatile Path cached;
    private volatile boolean attempted;
    private volatile String version = "";
    private volatile String failure = "";

    /** 使用当前工作目录作为游戏目录。 */
    public FfmpegLocator() {
        this(defaultGameDir());
    }

    /** 指定游戏目录（通常是 .minecraft）。 */
    public FfmpegLocator(Path gameDir) {
        this.gameDir = gameDir == null ? defaultGameDir() : gameDir.toAbsolutePath().normalize();
    }

    /** 默认游戏目录：优先系统属性 {@code mikuvideo.gameDir}，否则用进程工作目录。 */
    public static Path defaultGameDir() {
        String override = System.getProperty("mikuvideo.gameDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override.strip()).toAbsolutePath().normalize();
        }
        String cwd = System.getProperty("user.dir");
        return Path.of(cwd == null || cwd.isBlank() ? "." : cwd).toAbsolutePath().normalize();
    }

    /** 当前平台下 ffmpeg 的可执行文件名。 */
    public static String executableName() {
        return isWindows() ? "ffmpeg.exe" : "ffmpeg";
    }

    public Path gameDir() {
        return gameDir;
    }

    /** 游戏目录内放置 ffmpeg 的目录：{@code <gameDir>/mikuvideo/ffmpeg}。 */
    public Path bundleDir() {
        return gameDir.resolve("mikuvideo").resolve("ffmpeg");
    }

    /** 显式配置的路径，未配置时为 {@code null}。 */
    public String explicitPath() {
        return explicitPath;
    }

    /** 设置显式路径；传空表示清除。支持直接给可执行文件或它所在的目录。 */
    public void setPath(String path) {
        this.explicitPath = path == null || path.isBlank() ? null : path.strip();
        invalidate();
    }

    /** 清空缓存，下次 {@link #locate()} 重新按顺序探测。 */
    public void reload() {
        invalidate();
    }

    /**
     * 返回可用的 ffmpeg 路径，找不到返回 {@code null}。
     *
     * <p>结果缓存；命中 PATH 的 bare 名称时返回的是 {@code ffmpeg} 这样的命令名，可直接交给
     * {@link ProcessBuilder} 二次解析。</p>
     */
    public Path locate() {
        if (attempted) {
            return cached;
        }
        // 另一个线程正在扫描时不阻塞调用方：主线程只拿缓存，扫描完成后下次调用自然命中。
        if (!scanLock.tryLock()) {
            return cached;
        }
        try {
            if (attempted) {
                return cached;
            }
            long scanGeneration = generation;
            Path found = scan();
            if (scanGeneration != generation) {
                // 扫描期间被 setPath/reload 作废，丢弃这次结果。
                return cached;
            }
            cached = found;
            attempted = true;
            if (found == null && failure.isEmpty()) {
                failure = "未在任何位置找到 ffmpeg";
            }
            return found;
        } finally {
            scanLock.unlock();
        }
    }

    /** 是否已经完成过一次定位（无论成功与否）。用于在调用线程上避免阻塞探测。 */
    public boolean isResolved() {
        return attempted;
    }

    /** 命中版本的短字符串，例如 {@code n9.0.2-47-gb204e94e90-20261106}；未命中为空串。 */
    public String version() {
        locate();
        return version;
    }

    /** 最近一次失败原因；全部命中时为空串。 */
    public String failureReason() {
        locate();
        return failure;
    }

    /** 给界面用的单行描述。 */
    public String describe() {
        Path found = locate();
        if (found == null) {
            return "未找到 ffmpeg（" + failure + "）";
        }
        String v = version;
        return "ffmpeg " + (v.isEmpty() ? "" : v + " ") + "[" + found + "]";
    }

    private void invalidate() {
        cached = null;
        attempted = false;
        version = "";
        failure = "";
        generation++;
    }

    private Path scan() {
        for (Path candidate : candidates()) {
            Path hit = probe(candidate);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 按优先级生成候选项；不存在的路径也会被探测逻辑跳过。 */
    private List<Path> candidates() {
        List<Path> list = new ArrayList<>();
        String name = executableName();

        String explicit = explicitPath;
        if (explicit != null) {
            Path path = Path.of(explicit);
            if (Files.isDirectory(path)) {
                list.add(path.resolve(name));
            } else {
                list.add(path);
            }
        }

        // 系统 PATH：先让系统查一次完整路径，失败再交给 ProcessBuilder 直接解析命令名。
        for (String where : whereOutput()) {
            list.add(Path.of(where));
        }
        list.add(Path.of(name));

        if (isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                list.add(Path.of(localAppData, "Microsoft", "WinGet", "Links", name));
            }
            list.add(Path.of("C:\\ffmpeg\\bin", name));
        } else {
            list.add(Path.of("/usr/bin", name));
            list.add(Path.of("/usr/local/bin", name));
            list.add(Path.of("/opt/homebrew/bin", name));
            list.add(Path.of("/snap/bin", name));
            list.add(Path.of("/opt/local/bin", name));
        }

        // 游戏目录：<gameDir>/mikuvideo/ffmpeg/ffmpeg[.exe] 以及下载器生成的 <平台-版本>/ 子目录。
        Path bundle = bundleDir();
        list.add(bundle.resolve(name));
        list.addAll(listSubdirectoryBinaries(bundle, name));

        // 下载目录：用户手动放了 ffmpeg 的情况。
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            Path downloads = Path.of(home, "Downloads");
            list.add(downloads.resolve(name));
            list.add(downloads.resolve("ffmpeg").resolve(name));
            list.add(downloads.resolve("mikuvideo").resolve("ffmpeg").resolve(name));
            list.addAll(listSubdirectoryBinaries(downloads.resolve("mikuvideo").resolve("ffmpeg"), name));
        }
        return list;
    }

    /** 列出子目录里的可执行文件，按目录名倒序（版本号新的优先）。 */
    private static List<Path> listSubdirectoryBinaries(Path root, String name) {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (var stream = Files.list(root)) {
            List<Path> dirs = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (Path dir : dirs) {
                result.add(dir.resolve(name));
                result.add(dir.resolve("bin").resolve(name));
            }
        } catch (IOException ignored) {
            // 目录不可读时直接跳过，不影响其它候选
        }
        return result;
    }

    private static List<String> whereOutput() {
        List<String> lines = new ArrayList<>();
        if (!isWindows()) {
            return lines;
        }
        try {
            Process process = new ProcessBuilder("where", "ffmpeg").redirectErrorStream(true).start();
            String output = readAll(process.getInputStream(), 8 * 1024);
            if (process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && process.exitValue() == 0) {
                for (String line : output.split("\\R")) {
                    if (!line.isBlank()) {
                        lines.add(line.strip());
                    }
                }
            } else {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 没有 where 命令（非 Windows 或精简系统）时静默跳过
        }
        return lines;
    }

    /**
     * 探测单个候选：跑 {@code ffmpeg -version}，3 秒内正常退出且输出含 "ffmpeg version" 才算命中。
     *
     * <p>输出必须在独立线程里排空，否则输出较多的程序会把管道写满并卡住 waitFor。</p>
     */
    private Path probe(Path candidate) {
        if (candidate == null) {
            return null;
        }
        // 绝对路径先看存在性：不存在的候选没必要拉起进程，也能让失败原因更干净
        if (candidate.isAbsolute() && !Files.isRegularFile(candidate)) {
            return null;
        }
        String command = candidate.toString();
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command, "-hide_banner", "-version");
            builder.redirectErrorStream(true);
            process = builder.start();
            final Process started = process;
            final StringBuffer sink = new StringBuffer();
            Thread drain = MediaThreads.daemon("probe", () -> sink.append(readAll(started.getInputStream(), PROBE_MAX_BYTES)));
            drain.setDaemon(true);
            drain.start();
            boolean exited = process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                failure = "探测超时：" + command;
                return null;
            }
            MediaThreads.join(drain, 800L);
            String output = sink.toString();
            if (process.exitValue() != 0) {
                failure = "退出码 " + process.exitValue() + "：" + command;
                return null;
            }
            String first = firstLine(output);
            if (!first.toLowerCase(Locale.ROOT).contains("ffmpeg version")) {
                failure = "输出不是 ffmpeg：" + command;
                return null;
            }
            version = first.replace("ffmpeg version", "").strip().split(" ")[0];
            failure = "";
            return candidate;
        } catch (IOException e) {
            failure = candidate.isAbsolute()
                    ? "无法执行 " + command + "：" + e.getMessage()
                    : "系统 PATH 中没有 ffmpeg";
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            failure = "探测失败：" + command + "：" + e.getMessage();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readAll(InputStream stream, int limit) {
        StringBuilder builder = new StringBuilder();
        byte[] buffer = new byte[4096];
        try (stream) {
            int read;
            while (builder.length() < limit && (read = stream.read(buffer)) > 0) {
                builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // 进程被销毁时读取会抛异常，已读到的内容仍然有效
        }
        return builder.toString();
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int index = text.indexOf('\n');
        return (index < 0 ? text : text.substring(0, index)).strip();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}