package dev.junxiex.mikuvideo.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.junxiex.mikuvideo.client.MikuVideoClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 客户端设置，保存在 config/mikuvideo.json。 */
public final class ClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file;

    public int masterVolume = 100;
    public int maxActiveStreams = 4;
    public int maxWidth = 960;
    public int maxHeight = 540;
    public int fps = 24;
    public int pixelsPerBlock = 96;
    public int brightness = 100;
    public double viewDistance = 96.0;
    public boolean audioEnabled = true;
    public boolean autoDownloadFfmpeg = false;
    public boolean allowPrivateHosts = false;
    public boolean debug = false;
    public String ffmpegPath = "";
    public String ytDlpPath = "";
    public String cookiesFile = "";
    public String proxy = "";
    public int qualityHeight = 1080;

    public static ClientConfig load() {
        file = FabricLoader.getInstance().getConfigDir().resolve("mikuvideo.json");
        ClientConfig config = new ClientConfig();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                ClientConfig loaded = GSON.fromJson(reader, ClientConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (Exception e) {
                MikuVideoClient.LOGGER.warn("读取客户端配置失败，使用默认值: {}", e.getMessage());
            }
        }
        config.validate();
        config.save();
        return config;
    }

    public void validate() {
        masterVolume = clamp(masterVolume, 0, 100);
        maxActiveStreams = clamp(maxActiveStreams, 0, 16);
        maxWidth = clamp(maxWidth, 128, 1920);
        maxHeight = clamp(maxHeight, 72, 1080);
        fps = clamp(fps, 5, 60);
        pixelsPerBlock = clamp(pixelsPerBlock, 16, 192);
        brightness = clamp(brightness, 10, 100);
        if (!Double.isFinite(viewDistance)) {
            viewDistance = 96.0;
        }
        viewDistance = Math.max(8.0, Math.min(512.0, viewDistance));
        qualityHeight = clamp(qualityHeight, 144, 2160);
        if (ffmpegPath == null) {
            ffmpegPath = "";
        }
        if (ytDlpPath == null) {
            ytDlpPath = "";
        }
        if (cookiesFile == null) {
            cookiesFile = "";
        }
        if (proxy == null) {
            proxy = "";
        }
    }

    public void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            MikuVideoClient.LOGGER.warn("保存客户端配置失败: {}", e.getMessage());
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}