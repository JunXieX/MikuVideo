package dev.junxiex.mikuvideo.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.junxiex.mikuvideo.config.Settings;
import dev.junxiex.mikuvideo.permission.Permissions;
import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.screen.Screen;
import dev.junxiex.mikuvideo.screen.ScreenGeometry;
import dev.junxiex.mikuvideo.screen.ScreenManager;
import dev.junxiex.mikuvideo.util.Text;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * 屏幕生命周期子命令：创建、删除、改名、尺寸、移动、可见性、音量。
 *
 * @author JunXieX
 */
public final class ScreenCommands {

    /** 默认宽高（16:9）。 */
    public static final double DEFAULT_WIDTH = 4.0D;
    public static final double DEFAULT_HEIGHT = 2.25D;
    /** 新建屏幕的默认音量。 */
    public static final int DEFAULT_VOLUME = 80;

    private final CommandSupport support;

    public ScreenCommands(CommandSupport support) {
        this.support = support;
    }

    /** 挂到根命令上。 */
    public void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("create")
                .requires(source -> source.getSender().hasPermission(Permissions.CREATE))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> create(context, null, null))
                        .then(Commands.argument("width", DoubleArgumentType.doubleArg(0.1D, 64.0D))
                                .executes(context -> create(context, DoubleArgumentType.getDouble(context, "width"), null))
                                .then(Commands.argument("height", DoubleArgumentType.doubleArg(0.1D, 64.0D))
                                        .executes(context -> create(context,
                                                DoubleArgumentType.getDouble(context, "width"),
                                                DoubleArgumentType.getDouble(context, "height")))))));

        root.then(Commands.literal("remove")
                .then(screenArgument("screen")
                        .executes(context -> remove(context.getSource(), StringArgumentType.getString(context, "screen")))));

        root.then(Commands.literal("rename")
                .then(screenArgument("screen")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> rename(context.getSource(),
                                        StringArgumentType.getString(context, "screen"),
                                        StringArgumentType.getString(context, "name"))))));

        root.then(Commands.literal("size")
                .then(screenArgument("screen")
                        .then(Commands.argument("width", DoubleArgumentType.doubleArg(0.1D, 64.0D))
                                .then(Commands.argument("height", DoubleArgumentType.doubleArg(0.1D, 64.0D))
                                        .executes(context -> size(context.getSource(),
                                                StringArgumentType.getString(context, "screen"),
                                                DoubleArgumentType.getDouble(context, "width"),
                                                DoubleArgumentType.getDouble(context, "height")))))));

        root.then(Commands.literal("move")
                .then(screenArgument("screen")
                        .executes(context -> move(context.getSource(), StringArgumentType.getString(context, "screen")))));

        root.then(Commands.literal("access")
                .then(screenArgument("screen")
                        .then(Commands.literal("public")
                                .executes(context -> access(context.getSource(),
                                        StringArgumentType.getString(context, "screen"), true)))
                        .then(Commands.literal("private")
                                .executes(context -> access(context.getSource(),
                                        StringArgumentType.getString(context, "screen"), false)))));

        root.then(Commands.literal("volume")
                .then(screenArgument("screen")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                .executes(context -> volume(context.getSource(),
                                        StringArgumentType.getString(context, "screen"),
                                        IntegerArgumentType.getInteger(context, "value"))))));
    }

    /** 生成带补全的屏幕参数。 */
    public RequiredArgumentBuilder<CommandSourceStack, String> screenArgument(String name) {
        SuggestionProvider<CommandSourceStack> provider = (context, builder) -> {
            support.suggestScreens(context.getSource().getSender(), builder);
            return builder.buildFuture();
        };
        return Commands.<String>argument(name, StringArgumentType.word()).suggests(provider);
    }

    private int create(CommandContext<CommandSourceStack> context, Double width, Double height) {
        CommandSourceStack source = context.getSource();
        if (!support.allow(source)) {
            return 0;
        }
        Player player = support.player(source);
        if (player == null) {
            return 0;
        }
        String name = StringArgumentType.getString(context, "name");
        support.tasks().runFor(player, () -> createNow(player, source, name, width, height));
        return 1;
    }

    private void createNow(Player player, CommandSourceStack source, String rawName, Double width, Double height) {
        String name = Text.trim(rawName, Protocol.MAX_SCREEN_NAME);
        if (name.isEmpty()) {
            support.error(source, "屏幕名称不能为空");
            return;
        }
        if (Text.utf8Length(name) > Protocol.MAX_SCREEN_NAME) {
            support.error(source, "名称过长，最多 " + Protocol.MAX_SCREEN_NAME + " 字节（约 21 个汉字）");
            return;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                support.error(source, "屏幕名称包含非法字符");
                return;
            }
        }
        Optional<ScreenGeometry.Hit> hit = ScreenGeometry.look(player);
        if (hit.isEmpty()) {
            support.error(source, "请看向 6 格内的方块表面");
            return;
        }
        Settings.Limits limits = support.settings().limits();
        float finalWidth = (float) limits.clampWidth(width == null ? DEFAULT_WIDTH : width);
        float finalHeight = (float) limits.clampHeight(height == null ? DEFAULT_HEIGHT : height);
        boolean unlimited = player.hasPermission(Permissions.LIMIT_UNLIMITED);
        ScreenManager.Creation creation = support.screens().create(player.getUniqueId(), name, hit.get(),
                finalWidth, finalHeight, DEFAULT_VOLUME, unlimited);
        switch (creation.error()) {
            case NONE -> {
                Screen screen = creation.screen();
                support.reply(source, "<green>已创建屏幕 <white>" + screen.name() + "</white>（ID "
                        + screen.id() + "），尺寸 " + screen.width() + " x " + screen.height());
                support.notice(player, "<gray>用 /mv play " + screen.id() + " <链接> 开始播放");
            }
            case DUPLICATE -> support.error(source, "这里 1 格内已经有同朝向的屏幕了");
            case LIMIT -> support.error(source, "屏幕数量已达上限（当前 "
                    + support.screens().countOwned(player.getUniqueId()) + " 块）");
            default -> support.error(source, "创建失败，请检查参数");
        }
    }

    private int remove(CommandSourceStack source, String token) {
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, token);
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        support.playback().forget(screen.id());
        support.screens().remove(screen.id());
        support.reply(source, "<green>已删除屏幕 " + screen.name() + "（" + screen.id() + "）");
        return 1;
    }

    private int rename(CommandSourceStack source, String token, String rawName) {
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, token);
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        String name = Text.trim(rawName, Protocol.MAX_SCREEN_NAME);
        if (name.isEmpty()) {
            support.error(source, "名称不能为空");
            return 0;
        }
        if (Text.utf8Length(name) > Protocol.MAX_SCREEN_NAME) {
            support.error(source, "名称过长，最多 " + Protocol.MAX_SCREEN_NAME + " 字节（约 21 个汉字）");
            return 0;
        }
        support.screens().replace(screen.withName(name));
        support.reply(source, "<green>已改名为 " + name);
        return 1;
    }

    private int size(CommandSourceStack source, String token, double width, double height) {
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, token);
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        Settings.Limits limits = support.settings().limits();
        float finalWidth = (float) limits.clampWidth(width);
        float finalHeight = (float) limits.clampHeight(height);
        support.screens().replace(screen.withSize(finalWidth, finalHeight));
        support.reply(source, "<green>尺寸已更新为 " + finalWidth + " x " + finalHeight);
        return 1;
    }

    private int move(CommandSourceStack source, String token) {
        if (!support.allow(source)) {
            return 0;
        }
        Player player = support.player(source);
        if (player == null) {
            return 0;
        }
        Screen screen = support.require(source, token);
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        support.tasks().runFor(player, () -> {
            Optional<ScreenGeometry.Hit> hit = ScreenGeometry.look(player);
            if (hit.isEmpty()) {
                support.error(source, "请看向 6 格内的方块表面");
                return;
            }
            support.screens().replace(screen.withGeometry(hit.get()));
            support.reply(source, "<green>屏幕已移动到当前视线位置");
        });
        return 1;
    }

    private int access(CommandSourceStack source, String token, boolean publicScreen) {
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, token);
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        support.screens().replace(screen.withPublic(publicScreen));
        support.reply(source, publicScreen ? "<green>已设为公开" : "<yellow>已设为私有（仅自己与管理员可见）");
        return 1;
    }

    private int volume(CommandSourceStack source, String token, int value) {
        if (!support.allow(source)) {
            return 0;
        }
        Screen screen = support.require(source, token);
        if (screen == null || !support.requireManage(source, screen)) {
            return 0;
        }
        support.playback().volume(screen, value);
        support.reply(source, "<green>音量已设为 " + Math.max(0, Math.min(100, value)));
        return 1;
    }
}
