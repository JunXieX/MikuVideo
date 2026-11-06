import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricApiVersion = providers.gradleProperty("fabric_api_version").get()

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}

loom {
    mods {
        register("mikuvideo") {
            sourceSet(sourceSets.main.get())
        }
    }
}

sourceSets {
    main {
        java {
            srcDir(rootProject.file("common/src/main/java"))
        }
    }
}

// ---------------------------------------------------------------------------
// 内置 FFmpeg：构建时下载官方构建、抽出可执行文件、压缩后打进 mod jar，
// 玩家首次播放时由 BundledFfmpeg 解压到 .minecraft/mikuvideo/ffmpeg/bundled-1/。
// 只打进允许再分发的平台，未列出的平台仍回退到系统 PATH / 手动下载。
// ---------------------------------------------------------------------------
val bundledFfmpegPlatforms = (providers.gradleProperty("bundled_ffmpeg_platforms").orNull
        ?: "windows-x64,linux-x64")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val ytdlpPlatforms = (providers.gradleProperty("bundled_ytdlp_platforms").orNull
        ?: "windows-x64,linux-x64")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val ytdlpSources = mapOf(
        "windows-x64" to "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe",
        "linux-x64" to "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"
)

val ffmpegSources = mapOf(
        "windows-x64" to "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n9.0-latest-win64-gpl-9.0.zip",
        "linux-x64" to "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n9.0-latest-linux64-gpl-9.0.tar.xz"
)

val generatedFfmpegDir = layout.buildDirectory.dir("generated/bundled-ffmpeg").get().asFile
val ffmpegCacheDir = layout.buildDirectory.dir("ffmpeg-cache").get().asFile

val prepareBundledFfmpeg = tasks.register("prepareBundledFfmpeg") {
    outputs.dir(generatedFfmpegDir)
    doLast {
        generatedFfmpegDir.mkdirs()
        ffmpegCacheDir.mkdirs()
        for (platform in bundledFfmpegPlatforms) {
            val url = ffmpegSources[platform]
            if (url == null) {
                println("没有为平台 " + platform + " 配置 FFmpeg 下载地址，跳过")
                continue
            }
            val archive = File(ffmpegCacheDir, url.substringAfterLast('/'))
            if (!archive.isFile || archive.length() == 0L) {
                println("下载 FFmpeg " + platform + ": " + url)
                archive.parentFile.mkdirs()
                URI(url).toURL().openStream().use { input ->
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val target = File(generatedFfmpegDir, "mikuvideo/ffmpeg/$platform.zip")
            if (target.isFile && target.length() > 1_048_576L) {
                continue
            }
            target.parentFile.mkdirs()
            val work = File(ffmpegCacheDir, "work-$platform")
            work.deleteRecursively()
            work.mkdirs()
            val binary = FfmpegBundler.extractBinary(archive, work)
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(binary.name))
                binary.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
            println("内置 FFmpeg " + platform + ": " + (target.length() / 1024 / 1024) + " MB")
        }
    }
}

val prepareBundledYtDlp = tasks.register("prepareBundledYtDlp") {
    outputs.dir(generatedFfmpegDir)
    doLast {
        for (platform in ytdlpPlatforms) {
            val url = ytdlpSources[platform]
            if (url == null) {
                println("没有为平台 " + platform + " 配置 yt-dlp 下载地址，跳过")
                continue
            }
            val binary = File(ffmpegCacheDir, "yt-dlp-" + platform + (if (platform.startsWith("windows")) ".exe" else ""))
            if (!binary.isFile || binary.length() == 0L) {
                println("下载 yt-dlp " + platform + ": " + url)
                binary.parentFile.mkdirs()
                URI(url).toURL().openStream().use { input ->
                    binary.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val target = File(generatedFfmpegDir, "mikuvideo/ytdlp/$platform.zip")
            if (target.isFile && target.length() > 1_048_576L) {
                continue
            }
            target.parentFile.mkdirs()
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(binary.name))
                binary.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
            println("内置 yt-dlp " + platform + ": " + (target.length() / 1024 / 1024) + " MB")
        }
    }
}

sourceSets.main {
    // 两个任务都写入同一个生成目录；这里只声明一次，避免资源重复。
    resources.srcDir(prepareBundledFfmpeg)
}

object FfmpegBundler {
    @JvmStatic
    fun extractBinary(archive: File, work: File): File {
    return when {
        archive.name.endsWith(".zip", ignoreCase = true) -> {
            ZipFile(archive).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { candidate ->
                    !candidate.isDirectory && (candidate.name.endsWith("ffmpeg.exe") || candidate.name.endsWith("/ffmpeg"))
                } ?: throw GradleException("压缩包里找不到 ffmpeg: ${archive.name}")
                val destination = File(work, entry.name.substringAfterLast('/'))
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                destination
            }
        }

        archive.name.endsWith(".tar.xz", ignoreCase = true) -> {
            val process = ProcessBuilder("tar", "-xJf", archive.absolutePath, "-C", work.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw GradleException("解压 FFmpeg 超时: ${archive.name}")
            }
            if (process.exitValue() != 0) {
                throw GradleException("解压 FFmpeg 失败: $output")
            }
            work.walkTopDown().firstOrNull { it.isFile && it.name == "ffmpeg" && it.parentFile?.name == "bin" }
                    ?: work.walkTopDown().firstOrNull { it.isFile && it.name == "ffmpeg" }
                    ?: throw GradleException("tar 包里找不到 ffmpeg: ${archive.name}")
        }

        else -> throw GradleException("不支持的 FFmpeg 压缩格式: ${archive.name}")
    }
    }
}

tasks.jar {
    archiveBaseName.set("MikuVideo-Client")
    // 内置 FFmpeg 已经是 zip 压缩数据，jar 再压一次只会浪费构建时间。
    entryCompression = org.gradle.api.tasks.bundling.ZipEntryCompression.STORED
}

tasks.processResources {
    dependsOn(prepareBundledFfmpeg)
    dependsOn(prepareBundledYtDlp)
    val version = project.version
    inputs.property("version", version)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to version))
    }
}