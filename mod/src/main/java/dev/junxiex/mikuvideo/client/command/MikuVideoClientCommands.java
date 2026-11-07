package dev.junxiex.mikuvideo.client.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.junxiex.mikuvideo.client.MikuVideoClient;
import dev.junxiex.mikuvideo.client.config.ClientConfig;
import dev.junxiex.mikuvideo.client.credential.CredentialService;
import dev.junxiex.mikuvideo.client.media.FfmpegMediaEngine;
import dev.junxiex.mikuvideo.client.media.MediaEngine;
import dev.junxiex.mikuvideo.client.screen.ScreenRegistry;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

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
                        statusAsync(source);
                        info(source, "屏幕数: " + ScreenRegistry.INSTANCE.size()
                                + "，同时解码上限: " + config.maxActiveStreams
                                + "，音量: " + config.masterVolume);
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
                                        statusAsync(ctx.getSource());
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
                    .then(ClientCommands.literal("search")
                            .then(ClientCommands.argument("screen", StringArgumentType.word())
                                    .then(ClientCommands.argument("keyword", StringArgumentType.greedyString())
                                            .executes(ctx -> {
                                                String screen = StringArgumentType.getString(ctx, "screen");
                                                String keyword = StringArgumentType.getString(ctx, "keyword").trim();
                                                FabricClientCommandSource source = ctx.getSource();
                                                ClientConfig config = MikuVideoClient.config();
                                                Path cookies = null;
                                                if (config.cookiesFile != null && !config.cookiesFile.isBlank()) {
                                                    try {
                                                        cookies = Path.of(config.cookiesFile);
                                                    } catch (RuntimeException ignored) {
                                                    }
                                                }
                                                info(source, "正在搜索 B 站：" + keyword);
                                                MikuVideoClient.media().resolver()
                                                        .searchBilibili(keyword, cookies, config.proxy)
                                                        .whenComplete((items, error) -> source.getClient().execute(() -> {
                                                            if (error != null) {
                                                                error(source, "搜索失败：" + rootMessage(error));
                                                                return;
                                                            }
                                                            if (items.isEmpty()) {
                                                                info(source, "没有找到结果");
                                                                return;
                                                            }
                                                            for (int index = 0; index < items.size(); index++) {
                                                                var item = items.get(index);
                                                                String label = "[" + (index + 1) + "] " + item.title()
                                                                        + "  §7" + item.author() + "  §8" + formatDuration(item.durationMillis());
                                                                Component line = Component.literal(label).withStyle(ChatFormatting.AQUA)
                                                                        .withStyle(style -> style
                                                                                .withClickEvent(new ClickEvent.RunCommand("/mv play " + screen + " " + item.url()))
                                                                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击播放到 " + screen + "：" + item.url()))));
                                                                source.sendFeedback(line);
                                                            }
                                                        }));
                                                return 1;
                                            }))))
                    .then(ClientCommands.literal("cookies")
                            .then(ClientCommands.argument("path", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String path = StringArgumentType.getString(ctx, "path").trim();
                                        ClientConfig config = MikuVideoClient.config();
                                        config.cookiesFile = path;
                                        config.save();
                                        info(ctx.getSource(), "B 站/网页视频 Cookie 文件已设为: " + path);
                                        if (!Path.of(path).toFile().isFile()) {
                                            error(ctx.getSource(), "该文件不存在；请导出 Netscape 格式 cookies.txt");
                                        }
                                        return 1;
                                    })))
                    .then(ClientCommands.literal("quality")
                            .then(ClientCommands.argument("value", StringArgumentType.word())
                                    .executes(ctx -> {
                                        String value = StringArgumentType.getString(ctx, "value").trim().toLowerCase();
                                        int height = switch (value) {
                                            case "best", "max" -> 2160;
                                            case "1080", "720", "480", "360" -> Integer.parseInt(value);
                                            default -> -1;
                                        };
                                        if (height < 0) {
                                            error(ctx.getSource(), "用法: /mikuvideo-client quality <360|480|720|1080|best>");
                                            return 0;
                                        }
                                        ClientConfig config = MikuVideoClient.config();
                                        config.qualityHeight = height;
                                        config.save();
                                        info(ctx.getSource(), "网页视频解析高度上限已设为 " + height + "p");
                                        return 1;
                                    })))
                    .then(ClientCommands.literal("douyinapi")
                            .then(ClientCommands.argument("value", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String value = StringArgumentType.getString(ctx, "value").trim();
                                        ClientConfig config = MikuVideoClient.config();
                                        config.douyinApi = value.equalsIgnoreCase("default") ? "" : value;
                                        config.save();
                                        MikuVideoClient.media().resolver().setDouyinApi(config.douyinApi);
                                        if (value.equalsIgnoreCase("off")) {
                                            info(ctx.getSource(), "已关闭抖音视频解析（直播不受影响）");
                                        } else if (value.equalsIgnoreCase("default")) {
                                            info(ctx.getSource(), "抖音视频解析已恢复默认接口");
                                        } else {
                                            info(ctx.getSource(), "抖音视频解析接口已设为: " + value);
                                        }
                                        return 1;
                                    })))
                    .then(ClientCommands.literal("resolver").executes(ctx -> {
                        FabricClientCommandSource source = ctx.getSource();
                        java.util.concurrent.CompletableFuture.supplyAsync(() -> MikuVideoClient.media().resolver().statusText())
                                .thenAccept(text -> source.getClient().execute(() -> info(source, "网页解析器: " + text)));
                        return 1;
                    }))
                    .then(ClientCommands.literal("credential")
                            .executes(ctx -> credentialStatus(ctx.getSource()))
                            .then(ClientCommands.literal("status")
                                    .executes(ctx -> credentialStatus(ctx.getSource())))
                            .then(ClientCommands.literal("refresh").executes(ctx -> {
                                CredentialService service = MikuVideoClient.credentials();
                                if (service == null) {
                                    error(ctx.getSource(), "凭证服务尚未初始化");
                                    return 0;
                                }
                                service.requestRefresh();
                                return 1;
                            }))
                            .then(ClientCommands.literal("clear").executes(ctx -> {
                                CredentialService service = MikuVideoClient.credentials();
                                if (service == null) {
                                    error(ctx.getSource(), "凭证服务尚未初始化");
                                    return 0;
                                }
                                service.clear();
                                info(ctx.getSource(), "已清除本服保存的 B 站凭证");
                                return 1;
                            })))
                    .then(ClientCommands.literal("diag").executes(ctx -> {
                        FabricClientCommandSource source = ctx.getSource();
                        for (String line : MikuVideoClient.media().diagnostics()) {
                            info(source, line);
                        }
                        return 1;
                    }))
                    .then(ClientCommands.literal("reload").executes(ctx -> {
                        MikuVideoClient.reloadConfig();
                        FabricClientCommandSource source = ctx.getSource();
                        info(source, "配置已重新载入");
                        statusAsync(source);
                        return 1;
                    }));
            dispatcher.register(root);
        });
    }

    private static int credentialStatus(FabricClientCommandSource source) {
        CredentialService service = MikuVideoClient.credentials();
        if (service == null) {
            error(source, "凭证服务尚未初始化");
            return 0;
        }
        info(source, service.statusText());
        return 1;
    }

    /** 状态文本涉及磁盘/PATH 探测，放到后台算完再回主线程打印。 */
    private static void statusAsync(FabricClientCommandSource source) {
        java.util.concurrent.CompletableFuture.supplyAsync(() -> MikuVideoClient.media().engine().statusText()
                        + "\n" + MikuVideoClient.media().resolver().statusText())
                .thenAccept(text -> source.getClient().execute(() -> {
                    for (String line : text.split("\n")) {
                        info(source, line);
                    }
                }));
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.toString() : cursor.getMessage();
    }

    private static String formatDuration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format("%d:%02d", seconds / 60L, seconds % 60L);
    }

    private static void info(FabricClientCommandSource source, String text) {
        source.sendFeedback(Component.literal("[MikuVideo] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    private static void error(FabricClientCommandSource source, String text) {
        source.sendError(Component.literal("[MikuVideo] " + text));
    }
}