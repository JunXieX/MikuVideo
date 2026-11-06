package dev.junxiex.mikuvideo.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.junxiex.mikuvideo.permission.Permissions;
import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.screen.Screen;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.List;

/**
 * 管理子命令：reload / list / remove / stats / purge。
 *
 * @author JunXieX
 */
public final class AdminCommands {

    /** 列表每页条目数。 */
    public static final int PAGE_SIZE = 10;

    private final CommandSupport support;
    private final ScreenCommands screenArguments;

    public AdminCommands(CommandSupport support, ScreenCommands screenArguments) {
        this.support = support;
        this.screenArguments = screenArguments;
    }

    /** 挂到根命令上。 */
    public void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("admin")
                .requires(source -> source.getSender().hasPermission(Permissions.ADMIN))
                .then(Commands.literal("reload").executes(context -> reload(context.getSource())))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> list(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("remove")
                        .then(screenArguments.screenArgument("screen")
                                .executes(context -> remove(context.getSource(),
                                        StringArgumentType.getString(context, "screen")))))
                .then(Commands.literal("stats").executes(context -> stats(context.getSource())))
                .then(Commands.literal("purge")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(worldSuggestions())
                                .executes(context -> purge(context.getSource(),
                                        StringArgumentType.getString(context, "world"))))));
    }

    private int reload(CommandSourceStack source) {
        if (!support.allow(source)) {
            return 0;
        }
        boolean ok = support.plugin().reloadSettings();
        if (ok) {
            support.reply(source, "<green>配置已重新加载，定时任务已按新参数重启");
            return 1;
        }
        support.error(source, "配置重载失败，已保留原有配置");
        return 0;
    }

    private int list(CommandSourceStack source, int page) {
        if (!support.allow(source)) {
            return 0;
        }
        List<Screen> all = support.screens().all();
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(1, Math.min(page, pages));
        support.reply(source, "<gray>全服屏幕 " + all.size() + " 块，第 " + current + " / " + pages + " 页");
        int from = (current - 1) * PAGE_SIZE;
        int to = Math.min(all.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            Screen screen = all.get(i);
            support.reply(source, "<gray>- <white>" + screen.id() + "</white> " + screen.name()
                    + "　" + screen.world() + "　" + (screen.publicScreen() ? "公开" : "私有")
                    + "　归属 " + screen.owner().toString().substring(0, 8));
        }
        return 1;
    }

    private int remove(CommandSourceStack source, String token) {
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, token);
        if (screen == null) {
            return 0;
        }
        support.playback().forget(screen.id());
        support.screens().remove(screen.id());
        support.reply(source, "<green>已强制删除屏幕 " + screen.name() + "（" + screen.id() + "）");
        return 1;
    }

    private int stats(CommandSourceStack source) {
        if (!support.allow(source)) {
            return 0;
        }
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        support.reply(source, "<gray>—— MikuVideo 运行状态 ——");
        support.reply(source, "<gray>协议版本：<white>" + Protocol.VERSION + "</white>　插件版本：<white>"
                + support.plugin().getPluginMeta().getVersion() + "</white>");
        support.reply(source, "<gray>屏幕总数：<white>" + support.screens().total() + "</white>　世界数：<white>"
                + support.screens().all().stream().map(Screen::world).distinct().count() + "</white>");
        support.reply(source, "<gray>已同步玩家：<white>" + support.sync().trackedPlayers()
                + "</white>　已握手客户端：<white>" + support.clients().size() + "</white>");
        long rtt = support.sync().averageRttMillis();
        support.reply(source, "<gray>发送失败次数：<white>" + support.sync().sendFailures()
                + "</white>　平均心跳耗时：" + (rtt < 0L ? "无样本" : rtt + " ms"));
        support.reply(source, "<gray>JVM 内存：" + used + " MiB");
        return 1;
    }

    private int purge(CommandSourceStack source, String world) {
        if (!support.allow(source)) {
            return 0;
        }
        List<Screen> removed = support.screens().removeWorld(world);
        for (Screen screen : removed) {
            support.playback().forget(screen.id());
        }
        support.reply(source, "<green>已清理世界 " + world + " 的 " + removed.size() + " 块屏幕");
        return removed.isEmpty() ? 0 : 1;
    }

    private SuggestionProvider<CommandSourceStack> worldSuggestions() {
        return (context, builder) -> {
            for (World world : Bukkit.getWorlds()) {
                builder.suggest(world.getName());
            }
            return builder.buildFuture();
        };
    }
}
