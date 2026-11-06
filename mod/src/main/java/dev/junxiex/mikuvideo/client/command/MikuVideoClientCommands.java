package dev.junxiex.mikuvideo.client.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.junxiex.mikuvideo.client.MikuVideoClient;
import dev.junxiex.mikuvideo.client.config.ClientConfig;
import dev.junxiex.mikuvideo.client.media.FfmpegMediaEngine;
import dev.junxiex.mikuvideo.client.media.MediaEngine;
import dev.junxiex.mikuvideo.client.net.ClockSync;
import dev.junxiex.mikuvideo.client.screen.ScreenRegistry;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/** 客户端 /mikuvideo-client 命令。 */
public final class MikuVideoClientCommands {
    private MikuVideoClientCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            var root = ClientCommands.literal("mikuvideo-client")
                    .then(ClientCommands.literal("status").executes(ctx -> {
                        FabricClientCommandSource source = ctx.getSource();
                        ClientConfig config = MikuVideoClient.config();
                        info(source, "解码引擎: " + MikuVideoClient.media().engine().statusText());
                        info(source, "屏幕数: " + ScreenRegistry.INSTANCE.size()
                                + "，同时解码上限: " + config.maxActiveStreams
                                + "，音量: " + config.masterVolume
                                + "，RTT: " + ClockSync.rttMillis() + "ms");
                        return 1;
                    }))
                    .then(ClientCommands.literal("volume")
                            .then(ClientCommands.argument("value", IntegerArgumentType.integer(0, 100))
                                    .executes(ctx -> {
                                        int value = IntegerArgumentType.getInteger(ctx, "value");
                                        ClientConfig config = MikuVideoClient.config();
                                        config.masterVolume = value;
                                        config.validate();
                                        config.save();
                                        info(ctx.getSource(), "主音量已设为 " + config.masterVolume);
                                        return 1;
                                    })))
                    .then(ClientCommands.literal("streams")
                            .then(ClientCommands.argument("value", IntegerArgumentType.integer(0, 16))
                                    .executes(ctx -> {
                                        int value = IntegerArgumentType.getInteger(ctx, "value");
                                        ClientConfig config = MikuVideoClient.config();
                                        config.maxActiveStreams = value;
                                        config.validate();
                                        config.save();
                                        info(ctx.getSource(), "同时解码上限已设为 " + config.maxActiveStreams);
                                        return 1;
                                    })))
                    .then(ClientCommands.literal("ffmpeg")
                            .then(ClientCommands.argument("path", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String path = StringArgumentType.getString(ctx, "path").trim();
                                        ClientConfig config = MikuVideoClient.config();
                                        config.ffmpegPath = path;
                                        config.save();
                                        MediaEngine engine = MikuVideoClient.media().engine();
                                        engine.setPreferredPath(path);
                                        if (engine instanceof FfmpegMediaEngine ffmpegEngine) {
                                            ffmpegEngine.reload();
                                        }
                                        info(ctx.getSource(), "ffmpeg 路径已设为: " + path);
                                        info(ctx.getSource(), engine.statusText());
                                        return 1;
                                    })))
                    .then(ClientCommands.literal("download").executes(ctx -> {
                        FabricClientCommandSource source = ctx.getSource();
                        MediaEngine engine = MikuVideoClient.media().engine();
                        if (!(engine instanceof FfmpegMediaEngine ffmpegEngine)) {
                            error(source, "当前解码后端不支持自动下载");
                            return 0;
                        }
                        info(source, "开始下载 ffmpeg，进度会显示在聊天栏…");
                        AtomicLong lastPercent = new AtomicLong(-10);
                        ffmpegEngine.downloadAsync(progress -> {
                            long percent = progress.total() > 0 ? progress.done() * 100L / progress.total() : -1L;
                            if (percent >= 0 && percent - lastPercent.get() >= 5) {
                                lastPercent.set(percent);
                                source.getClient().execute(() -> info(source, "下载中 " + percent + "%"));
                            }
                        }).whenComplete((path, throwable) -> source.getClient().execute(() -> {
                            if (throwable != null) {
                                error(source, "下载失败: " + throwable.getMessage());
                                return;
                            }
                            Path binary = path;
                            info(source, "下载完成: " + binary);
                            ClientConfig config = MikuVideoClient.config();
                            config.ffmpegPath = binary.toString();
                            config.save();
                        }));
                        return 1;
                    }))
                    .then(ClientCommands.literal("reload").executes(ctx -> {
                        MikuVideoClient.reloadConfig();
                        FabricClientCommandSource source = ctx.getSource();
                        info(source, "配置已重新载入");
                        info(source, MikuVideoClient.media().engine().statusText());
                        return 1;
                    }));
            dispatcher.register(root);
        });
    }

    private static void info(FabricClientCommandSource source, String text) {
        source.sendFeedback(Component.literal("[MikuVideo] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    private static void error(FabricClientCommandSource source, String text) {
        source.sendError(Component.literal("[MikuVideo] " + text));
    }
}
