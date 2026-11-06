package dev.junxiex.mikuvideo.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.junxiex.mikuvideo.MikuVideoPlugin;
import dev.junxiex.mikuvideo.permission.Permissions;
import dev.junxiex.mikuvideo.playback.PlaybackService;
import dev.junxiex.mikuvideo.protocol.Playback;
import dev.junxiex.mikuvideo.screen.Screen;
import dev.junxiex.mikuvideo.util.Text;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 命令树装配与根命令注册。
 *
 * <p>根命令 {@code /mikuvideo}（别名 {@code /mv}、{@code /video}）只用 Brigadier 注册，
 * 描述文件里没有 commands 段。</p>
 *
 * @author JunXieX
 */
public final class CommandService {

    /** 根命令名。 */
    public static final String ROOT = "mikuvideo";
    /** 别名。 */
    public static final List<String> ALIASES = List.of("mv", "video");

    private static final int LIST_PAGE_SIZE = 10;

    private final MikuVideoPlugin plugin;
    private final CommandSupport support;
    private final ScreenCommands screenCommands;
    private final PlaybackCommands playbackCommands;
    private final AdminCommands adminCommands;

    public CommandService(MikuVideoPlugin plugin, CommandSupport support) {
        this.plugin = plugin;
        this.support = support;
        this.screenCommands = new ScreenCommands(support);
        this.playbackCommands = new PlaybackCommands(support, screenCommands);
        this.adminCommands = new AdminCommands(support, screenCommands);
    }

    /** 在 onEnable 里调用，真正注册发生在 COMMANDS 生命周期事件里。 */
    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(build().build(), "MikuVideo 屏幕管理", ALIASES));
    }

    private LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT)
                .requires(source -> source.getSender().hasPermission(Permissions.USE))
                .executes(context -> {
                    help(context.getSource());
                    return 1;
                });

        root.then(Commands.literal("help").executes(context -> {
            help(context.getSource());
            return 1;
        }));

        root.then(Commands.literal("menu").executes(context -> {
            if (!support.allow(context.getSource())) {
                return 0;
            }
            Player player = support.player(context.getSource());
            if (player == null || !support.requireClient(player)) {
                return 0;
            }
            support.menu().openList(player, 0);
            return 1;
        }));

        root.then(Commands.literal("list")
                .executes(context -> list(context.getSource(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> list(context.getSource(),
                                IntegerArgumentType.getInteger(context, "page")))));

        root.then(Commands.literal("info")
                .then(screenCommands.screenArgument("screen")
                        .executes(context -> info(context.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "screen")))));

        screenCommands.attach(root);
        playbackCommands.attach(root);
        adminCommands.attach(root);
        return root;
    }

    private void help(CommandSourceStack source) {
        support.reply(source, "<gray>—— MikuVideo 命令 ——");
        support.reply(source, "<white>/mv menu</white> <dark_gray>·</dark_gray> 打开图形菜单");
        support.reply(source, "<white>/mv list [页码]</white> <dark_gray>·</dark_gray> 列出可管理的屏幕");
        support.reply(source, "<white>/mv info <屏幕></white> <dark_gray>·</dark_gray> 查看屏幕详情");
        support.reply(source, "<white>/mv create <名称> [宽] [高]</white> <dark_gray>·</dark_gray> 看向方块面创建");
        support.reply(source, "<white>/mv remove|rename|size|move|access|volume</white> <dark_gray>·</dark_gray> 维护屏幕");
        support.reply(source, "<white>/mv play <屏幕> <链接> [Referer]</white> <dark_gray>·</dark_gray> 播放");
        support.reply(source, "<white>/mv pause|resume|stop|seek <屏幕> [秒]</white> <dark_gray>·</dark_gray> 播放控制");
        support.reply(source, "<white>/mv admin reload|list|remove|stats|purge</white> <dark_gray>·</dark_gray> 管理命令");
    }

    private int list(CommandSourceStack source, int page) {
        if (!support.allow(source)) {
            return 0;
        }
        boolean all = source.getSender().hasPermission(Permissions.MANAGE_OTHERS);
        List<Screen> screens = all
                ? support.screens().all()
                : support.screens().ownedBy(source.getSender() instanceof Player player
                        ? player.getUniqueId() : null);
        int pages = Math.max(1, (screens.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
        int current = Math.max(1, Math.min(page, pages));
        support.reply(source, "<gray>共 " + screens.size() + " 块屏幕，第 " + current + " / " + pages + " 页");
        if (screens.isEmpty()) {
            support.reply(source, "<gray>还没有屏幕，用 <white>/mv create <名称></white> 看向方块面创建");
            return 1;
        }
        int from = (current - 1) * LIST_PAGE_SIZE;
        int to = Math.min(screens.size(), from + LIST_PAGE_SIZE);
        for (int i = from; i < to; i++) {
            Screen screen = screens.get(i);
            Playback state = support.playback().state(screen.id());
            String status = state.isStopped() ? "未播放" : (state.paused() ? "暂停" : "播放中");
            support.reply(source, "<gray>- <white>" + screen.id() + "</white> " + screen.name()
                    + "　" + screen.world() + "　" + (screen.publicScreen() ? "公开" : "私有")
                    + "　" + status);
        }
        return 1;
    }

    private int info(CommandSourceStack source, String token) {
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, token);
        if (screen == null) {
            return 0;
        }
        Playback state = support.playback().state(screen.id());
        long position = state.positionAt(System.currentTimeMillis());
        Player owner = Bukkit.getPlayer(screen.owner());
        String ownerName = owner != null ? owner.getName() : screen.owner().toString().substring(0, 8);
        support.reply(source, "<gray>—— 屏幕 " + screen.name() + " ——");
        support.reply(source, "<gray>ID：<white>" + screen.id() + "</white>　归属：<white>" + ownerName + "</white>");
        support.reply(source, "<gray>世界：<white>" + screen.world() + "</white>　中心：<white>"
                + Text.number(screen.cx()) + ", " + Text.number(screen.cy()) + ", " + Text.number(screen.cz())
                + "</white>");
        support.reply(source, "<gray>法线：<white>" + Text.number(screen.nx()) + ", "
                + Text.number(screen.ny()) + ", " + Text.number(screen.nz()) + "</white>　尺寸：<white>"
                + screen.width() + " x " + screen.height() + "</white>");
        support.reply(source, "<gray>可见性：<white>" + (screen.publicScreen() ? "公开" : "私有")
                + "</white>　音量：<white>" + screen.volume() + "</white>");
        support.reply(source, "<gray>链接：<white>" + (state.isStopped() ? "未设置" : state.url()) + "</white>");
        support.reply(source, "<gray>状态：<white>" + (state.isStopped() ? "未播放" : (state.paused() ? "暂停" : "播放中")) + "</white>　进度：<white>"
                + Text.duration(position) + " / "
                + (state.durationMillis() > 0L ? Text.duration(state.durationMillis()) : "未知") + "</white>");
        return 1;
    }
}