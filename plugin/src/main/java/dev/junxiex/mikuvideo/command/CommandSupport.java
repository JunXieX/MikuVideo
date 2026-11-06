package dev.junxiex.mikuvideo.command;

import dev.junxiex.mikuvideo.MikuVideoPlugin;
import dev.junxiex.mikuvideo.config.Settings;
import dev.junxiex.mikuvideo.menu.MenuService;
import dev.junxiex.mikuvideo.net.ClientRegistry;
import dev.junxiex.mikuvideo.net.SyncService;
import dev.junxiex.mikuvideo.permission.Permissions;
import dev.junxiex.mikuvideo.playback.PlaybackService;
import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.screen.Screen;
import dev.junxiex.mikuvideo.screen.ScreenManager;
import dev.junxiex.mikuvideo.util.Tasks;
import dev.junxiex.mikuvideo.util.Text;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子命令共用的上下文与校验。
 *
 * <p>所有“找不到 / 没权限 / 太频繁”的分支都在这里统一处理，
 * 子命令只关心业务动作，避免各处提示文案漂移。</p>
 *
 * @author JunXieX
 */
public final class CommandSupport {

    private final MikuVideoPlugin plugin;
    private final ScreenManager screens;
    private final PlaybackService playback;
    private final SyncService sync;
    private final ClientRegistry clients;
    private final MenuService menu;
    private final Tasks tasks;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public CommandSupport(MikuVideoPlugin plugin, ScreenManager screens, PlaybackService playback,
                          SyncService sync, ClientRegistry clients, MenuService menu, Tasks tasks) {
        this.plugin = plugin;
        this.screens = screens;
        this.playback = playback;
        this.sync = sync;
        this.clients = clients;
        this.menu = menu;
        this.tasks = tasks;
    }

    public MikuVideoPlugin plugin() {
        return plugin;
    }

    public ScreenManager screens() {
        return screens;
    }

    public PlaybackService playback() {
        return playback;
    }

    public SyncService sync() {
        return sync;
    }

    public ClientRegistry clients() {
        return clients;
    }

    public MenuService menu() {
        return menu;
    }

    public Tasks tasks() {
        return tasks;
    }

    public Settings settings() {
        return plugin.settings();
    }

    /** 普通反馈。 */
    public void reply(CommandSourceStack source, String message) {
        if (source == null) {
            return;
        }
        source.getSender().sendMessage(Text.prefixed(settings().messages().prefix(), message));
    }

    /** 错误反馈。 */
    public void error(CommandSourceStack source, String message) {
        reply(source, "<red>" + message);
    }

    /** 只给玩家发送提示（聊天栏 + 客户端悬浮提示）。 */
    public void notice(Player player, String message) {
        if (player == null) {
            return;
        }
        player.sendMessage(Text.prefixed(settings().messages().prefix(), message));
        sync.notice(player, Protocol.NOTICE_INFO, message);
    }

    /**
     * 命令冷却。控制台不限制；超频时静默丢弃，避免刷屏。
     */
    public boolean allow(CommandSourceStack source) {
        if (source == null || !(source.getSender() instanceof Player player)) {
            return true;
        }
        long cooldown = settings().sync().commandCooldownMs();
        if (cooldown <= 0L) {
            return true;
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        Long last = cooldowns.get(id);
        if (last != null && now - last < cooldown) {
            return false;
        }
        cooldowns.put(id, now);
        if (cooldowns.size() > 512) {
            cooldowns.entrySet().removeIf(entry -> now - entry.getValue() > 60000L);
        }
        return true;
    }

    /** 命令发送者对应的玩家，控制台执行时回一条提示并返回 null。 */
    public Player player(CommandSourceStack source) {
        if (source != null && source.getSender() instanceof Player player) {
            return player;
        }
        error(source, "该命令只能由玩家执行");
        return null;
    }

    /** 解析屏幕参数，失败时已经回复过错误。 */
    public Screen require(CommandSourceStack source, String token) {
        if (source == null) {
            return null;
        }
        CommandSender sender = source.getSender();
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        boolean all = sender.hasPermission(Permissions.MANAGE_OTHERS);
        ScreenManager.Resolution resolution = screens.resolve(token, viewer, all);
        if (!resolution.ok()) {
            String reason = resolution.error().isEmpty()
                    ? settings().messages().screenNotFound()
                    : resolution.error();
            error(source, reason);
            return null;
        }
        return resolution.screen();
    }

    /** 校验对某块屏幕的管理权限。 */
    public boolean requireManage(CommandSourceStack source, Screen screen) {
        if (source == null || screen == null) {
            return false;
        }
        if (!(source.getSender() instanceof Player player)) {
            return true;
        }
        if (screen.ownedBy(player.getUniqueId())) {
            if (!player.hasPermission(Permissions.MANAGE_OWN)) {
                error(source, settings().messages().noPermission());
                return false;
            }
            return true;
        }
        if (!player.hasPermission(Permissions.MANAGE_OTHERS)) {
            error(source, settings().messages().noPermission());
            return false;
        }
        return true;
    }

    /** 校验玩家是否装了客户端模组。 */
    public boolean requireClient(Player player) {
        if (player == null) {
            return false;
        }
        if (!clients.isRegistered(player.getUniqueId())) {
            player.sendMessage(Text.prefixed(settings().messages().prefix(), settings().messages().noClient()));
            return false;
        }
        return true;
    }

    /** 补全用的屏幕 ID 与名称。 */
    public void suggestScreens(CommandSender sender, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        boolean all = sender.hasPermission(Permissions.MANAGE_OTHERS);
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (Screen screen : screens.all()) {
            if (!screens.visible(screen, viewer, all)) {
                continue;
            }
            if (screen.id().startsWith(remaining)) {
                builder.suggest(screen.id());
            }
            String name = screen.name();
            if (name.indexOf(' ') < 0 && name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(name);
            }
        }
    }
}
