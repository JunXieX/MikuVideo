package dev.junxiex.mikuvideo.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.screen.Screen;
import dev.junxiex.mikuvideo.util.Text;
import dev.junxiex.mikuvideo.util.Urls;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/**
 * 播放控制子命令。
 *
 * <p>服务端只推进时间线，不下载也不代理视频；这里的 URL 校验只是把明显不该发的地址挡在入口。</p>
 *
 * @author JunXieX
 */
public final class PlaybackCommands {

    private final CommandSupport support;
    private final ScreenCommands screenArguments;

    public PlaybackCommands(CommandSupport support, ScreenCommands screenArguments) {
        this.support = support;
        this.screenArguments = screenArguments;
    }

    /** 挂到根命令上。 */
    public void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("play")
                .then(screenArguments.screenArgument("screen")
                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                .executes(this::play))));

        root.then(Commands.literal("pause")
                .then(screenArguments.screenArgument("screen")
                        .executes(context -> pause(context.getSource(), StringArgumentType.getString(context, "screen")))));

        root.then(Commands.literal("resume")
                .then(screenArguments.screenArgument("screen")
                        .executes(context -> resume(context.getSource(), StringArgumentType.getString(context, "screen")))));

        root.then(Commands.literal("stop")
                .then(screenArguments.screenArgument("screen")
                        .executes(context -> stop(context.getSource(), StringArgumentType.getString(context, "screen")))));

        root.then(Commands.literal("queue")
                .then(Commands.literal("add")
                        .then(screenArguments.screenArgument("screen")
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(this::queueAdd))))
                .then(Commands.literal("list")
                        .then(screenArguments.screenArgument("screen")
                                .executes(this::queueList)))
                .then(Commands.literal("clear")
                        .then(screenArguments.screenArgument("screen")
                                .executes(this::queueClear)))
                .then(Commands.literal("next")
                        .then(screenArguments.screenArgument("screen")
                                .executes(this::queueNext))));

        root.then(Commands.literal("seek")
                .then(screenArguments.screenArgument("screen")
                        .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0.0D, 86400.0D))
                                .executes(context -> seek(context.getSource(),
                                        StringArgumentType.getString(context, "screen"),
                                        DoubleArgumentType.getDouble(context, "seconds"))))));
    }

    private int play(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, StringArgumentType.getString(context, "screen"));
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        // greedyString 会吞掉整行：第一个空白前是 URL，后面是可选 Referer。
        String raw = StringArgumentType.getString(context, "url").trim();
        String[] parts = raw.split("\\s+", 2);
        String rawUrl = parts.length == 0 ? "" : parts[0];
        String referer = parts.length > 1 ? parts[1].trim() : null;
        Urls.Checked checked = Urls.validate(rawUrl, referer, support.settings().media());
        if (!checked.ok()) {
            support.error(source, checked.message());
            return 0;
        }
        support.playback().play(screen, checked.url(), checked.headers());
        support.reply(source, "<green>已提交播放：<gray>" + Text.trim(checked.url(), 96)
                + "</gray>（缓冲 " + support.settings().sync().startHoldMs() + " 毫秒后开始计时）");
        return 1;
    }

    private int queueAdd(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, StringArgumentType.getString(context, "screen"));
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        String raw = StringArgumentType.getString(context, "url").trim();
        String[] parts = raw.split("\\s+", 2);
        String rawUrl = parts.length == 0 ? "" : parts[0];
        String referer = parts.length > 1 ? parts[1].trim() : null;
        Urls.Checked checked = Urls.validate(rawUrl, referer, support.settings().media());
        if (!checked.ok()) {
            support.error(source, checked.message());
            return 0;
        }
        if (!support.playback().queueAdd(screen, checked.url(), checked.headers())) {
            support.error(source, "队列已满（最多 64 项）");
            return 0;
        }
        support.reply(source, "<green>已加入队列：<gray>" + Text.trim(checked.url(), 96)
                + "</gray>（当前 " + support.playback().queueList(screen.id()).size() + " 项）");
        return 1;
    }

    private int queueList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, StringArgumentType.getString(context, "screen"));
        if (screen == null) {
            return 0;
        }
        var queue = support.playback().queueList(screen.id());
        support.reply(source, "<gray>队列 " + screen.name() + "：" + queue.size() + " 项");
        for (int index = 0; index < queue.size(); index++) {
            support.reply(source, "<gray>" + (index + 1) + ". <white>" + Text.trim(queue.get(index).url(), 120));
        }
        return 1;
    }

    private int queueClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, StringArgumentType.getString(context, "screen"));
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        int removed = support.playback().queueClear(screen.id());
        support.reply(source, "<yellow>已清空队列（" + removed + " 项）");
        return 1;
    }

    private int queueNext(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, StringArgumentType.getString(context, "screen"));
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        if (!support.playback().queueNext(screen)) {
            support.error(source, "队列为空");
            return 0;
        }
        support.reply(source, "<green>已切换到队列下一项");
        return 1;
    }

    private int pause(CommandSourceStack source, String token) {
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, token);
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        if (!support.playback().pause(screen)) {
            support.error(source, "该屏幕当前没有正在播放的内容");
            return 0;
        }
        support.reply(source, "<yellow>已暂停 " + screen.name());
        return 1;
    }

    private int resume(CommandSourceStack source, String token) {
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, token);
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        if (!support.playback().resume(screen)) {
            support.error(source, "该屏幕当前没有可继续的内容");
            return 0;
        }
        support.reply(source, "<green>已继续播放 " + screen.name());
        return 1;
    }

    private int stop(CommandSourceStack source, String token) {
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, token);
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        support.playback().stop(screen);
        support.reply(source, "<yellow>已停止 " + screen.name());
        return 1;
    }

    private int seek(CommandSourceStack source, String token, double seconds) {
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, token);
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        if (!support.playback().seek(screen, seconds)) {
            support.error(source, "该屏幕当前没有可跳转的内容");
            return 0;
        }
        long position = support.playback().state(screen.id()).positionMillis();
        support.reply(source, "<green>已跳转到 " + Text.duration(position));
        return 1;
    }

    /** 便于外部复用的 URL 上限提示。 */
    public static String describeLimit(int limit) {
        return "链接长度上限 " + Math.min(limit, Protocol.MAX_URL) + " 字符";
    }
}