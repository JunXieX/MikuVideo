package dev.junxiex.mikuvideo.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.junxiex.mikuvideo.client.command.MikuVideoClientCommands;
import dev.junxiex.mikuvideo.client.config.ClientConfig;
import dev.junxiex.mikuvideo.client.interact.ScreenPicker;
import dev.junxiex.mikuvideo.client.playback.MediaManager;
import dev.junxiex.mikuvideo.client.net.ClientNetworking;
import dev.junxiex.mikuvideo.client.net.ClockSync;
import dev.junxiex.mikuvideo.client.render.ScreenRenderer;
import dev.junxiex.mikuvideo.client.screen.ClientScreen;
import dev.junxiex.mikuvideo.client.screen.ScreenRegistry;
import dev.junxiex.mikuvideo.protocol.Message;
import dev.junxiex.mikuvideo.protocol.Playback;
import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.protocol.ScreenInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 客户端入口：网络、渲染、解码预算与交互。 */
public final class MikuVideoClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("MikuVideo");
    private static final int STATUS_INTERVAL_TICKS = 40;

    private static ClientConfig config = new ClientConfig();
    private static boolean warnedMissingFfmpeg;
    private KeyMapping interactKey;
    private int tickCounter;
    private String lastOverlay = "";

    @Override
    public void onInitializeClient() {
        config = ClientConfig.load();
        media().engine().setPreferredPath(config.ffmpegPath);
        media().engine().setAutoDownload(config.autoDownloadFfmpeg);
        media().resolver().setDouyinApi(config.douyinApi);
        startFfmpegInit();

        ScreenRenderer.register();
        ClientNetworking.register();
        MikuVideoClientCommands.register();

        interactKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mikuvideo.interact",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_V,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mikuvideo", "main"))));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientNetworking.onJoin();
            warnedMissingFfmpeg = false;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ScreenRegistry.INSTANCE.clear();
            media().closeAll();
            ClockSync.reset();
        });

    }

    /** 在后台完成首次定位，避免主线程卡在 PATH/磁盘扫描上。 */
    private static void startFfmpegInit() {
        Thread thread = new Thread(() -> {
            try {
                if (media().engine().isAvailable()) {
                    LOGGER.info("FFmpeg 就绪: {}", media().engine().statusText());
                } else {
                    LOGGER.warn("FFmpeg 不可用: {}", media().engine().statusText());
                }
                LOGGER.info("网页解析器: {}", media().resolver().statusText());
            } catch (RuntimeException error) {
                LOGGER.warn("FFmpeg 初始化检查失败: {}", error.getMessage());
            }
        }, "mikuvideo-ffmpeg-init");
        thread.setDaemon(true);
        thread.start();
    }

    public static ClientConfig config() {
        return config;
    }

    public static void reloadConfig() {
        config = ClientConfig.load();
        media().engine().setPreferredPath(config.ffmpegPath);
        media().engine().setAutoDownload(config.autoDownloadFfmpeg);
        media().resolver().setDouyinApi(config.douyinApi);
    }

    public static MediaManager media() {
        return MediaManager.INSTANCE;
    }

    private void tick(Minecraft minecraft) {
        tickCounter++;
        ClientNetworking.tick(minecraft);
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        ScreenRegistry.INSTANCE.uploadFrames(minecraft);
        media().tick(minecraft);
        if (!warnedMissingFfmpeg && !media().engine().isAvailable()) {
            warnedMissingFfmpeg = true;
            chat("未找到 ffmpeg，视频无法解码。安装 ffmpeg 后执行 /mikuvideo-client reload，或执行 /mikuvideo-client download 自动下载，或用 /mikuvideo-client ffmpeg <路径> 指定。");
        }

        while (interactKey.consumeClick()) {
            ScreenPicker.Hit hit = ScreenPicker.pick(minecraft, 32.0);
            if (hit != null) {
                ClientNetworking.send(new Message.InteractC2S(hit.screen().info().id(), Protocol.INTERACT_OPEN_MENU));
            } else {
                minecraft.player.sendOverlayMessage(Component.literal("没有看向任何屏幕").withStyle(ChatFormatting.GRAY));
            }
        }
        if (tickCounter % STATUS_INTERVAL_TICKS == 0) {
            ScreenPicker.Hit hit = ScreenPicker.pick(minecraft, 32.0);
            if (hit != null) {
                String text = hit.screen().statusText(config);
                if (!text.equals(lastOverlay)) {
                    lastOverlay = text;
                    minecraft.player.sendOverlayMessage(Component.literal(text));
                }
            } else {
                lastOverlay = "";
            }
        }
    }

    public static void handle(Message message) {
        Minecraft minecraft = Minecraft.getInstance();
        switch (message) {
            case Message.HelloS2C hello -> {
                if (hello.protocolVersion() != Protocol.VERSION) {
                    chat("协议版本不一致：服务端 " + hello.protocolVersion() + "，客户端 " + Protocol.VERSION + "，请更新模组");
                    return;
                }
                ClientNetworking.onServerHello();
                ClockSync.updateFromHello(hello.serverEpochMillis());
            }
            case Message.ScreenSyncS2C sync -> ScreenRegistry.INSTANCE.replaceAll(sync.screens());
            case Message.ScreenUpsertS2C upsert -> ScreenRegistry.INSTANCE.upsert(upsert.screen());
            case Message.ScreenRemoveS2C remove -> ScreenRegistry.INSTANCE.remove(remove.screenId());
            case Message.PlaybackS2C playback -> {
                ClientScreen screen = ScreenRegistry.INSTANCE.get(playback.screenId());
                if (screen != null) {
                    screen.setPlayback(playback.playback());
                }
            }
            case Message.ClearS2C ignored -> {
                ScreenRegistry.INSTANCE.clear();
                media().closeAll();
            }
            case Message.NoticeS2C notice -> {
                if (notice.level() >= Protocol.NOTICE_ERROR) {
                    ClientNetworking.stopHello();
                }
                if (minecraft.player != null) {
                    ChatFormatting color = notice.level() >= Protocol.NOTICE_ERROR ? ChatFormatting.RED
                            : notice.level() == Protocol.NOTICE_WARN ? ChatFormatting.YELLOW : ChatFormatting.GRAY;
                    String text = notice.message().replaceAll("<[^>]+>", "");
                    minecraft.player.sendSystemMessage(Component.literal("[MikuVideo] ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal(text).withStyle(color)));
                }
            }
            case Message.PingS2C ping -> ClientNetworking.send(new Message.PongC2S(ping.id(), System.currentTimeMillis()));
            default -> {
                // 客户端不处理服务端以外的消息。
            }
        }
    }

    private static void chat(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal("[MikuVideo] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
        }
    }

    /** 供状态文本使用。 */
    public static Playback playbackOf(String screenId) {
        ClientScreen screen = ScreenRegistry.INSTANCE.get(screenId);
        return screen == null ? Playback.stopped() : screen.playback();
    }

    /** 供调试使用。 */
    public static ScreenInfo infoOf(String screenId) {
        ClientScreen screen = ScreenRegistry.INSTANCE.get(screenId);
        return screen == null ? null : screen.info();
    }
}