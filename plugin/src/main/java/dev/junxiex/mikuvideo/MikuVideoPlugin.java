package dev.junxiex.mikuvideo;

import dev.junxiex.mikuvideo.command.CommandService;
import dev.junxiex.mikuvideo.command.CommandSupport;
import dev.junxiex.mikuvideo.config.Settings;
import dev.junxiex.mikuvideo.listener.PlayerListener;
import dev.junxiex.mikuvideo.menu.MenuService;
import dev.junxiex.mikuvideo.net.ClientRegistry;
import dev.junxiex.mikuvideo.net.PacketListener;
import dev.junxiex.mikuvideo.net.SyncService;
import dev.junxiex.mikuvideo.permission.Permissions;
import dev.junxiex.mikuvideo.playback.PlaybackService;
import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.screen.Screen;
import dev.junxiex.mikuvideo.screen.ScreenManager;
import dev.junxiex.mikuvideo.screen.ScreenStorage;
import dev.junxiex.mikuvideo.util.Tasks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

/**
 * MikuVideo 服务端插件主体。
 *
 * <p>所有服务在 {@code onEnable} 手工按依赖顺序装配，任意一步失败立刻禁用插件并写清原因，
 * 绝不带着半个状态继续跑；{@code onDisable} 按“停调度 → 注销注册 → flush 存储”的顺序收尾。</p>
 *
 * @author JunXieX
 */
public final class MikuVideoPlugin extends JavaPlugin {

    private volatile Settings settings = Settings.defaults();
    private Tasks tasks;
    private ScreenManager screenManager;
    private PlaybackService playbackService;
    private ScreenStorage storage;
    private ClientRegistry clientRegistry;
    private SyncService syncService;
    private MenuService menuService;
    private PacketListener packetListener;
    private CommandService commandService;
    private ScheduledTask rescanTask;
    private ScheduledTask rebroadcastTask;
    private ScheduledTask pingTask;

    @Override
    public void onEnable() {
        try {
            enableServices();
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "MikuVideo 初始化失败，插件将被禁用", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        stopTimers();
        if (packetListener != null) {
            try {
                getServer().getMessenger().unregisterIncomingPluginChannel(this, Protocol.CHANNEL);
            } catch (RuntimeException error) {
                getLogger().log(Level.WARNING, "注销入站通道失败", error);
            }
        }
        try {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, Protocol.CHANNEL);
        } catch (RuntimeException error) {
            getLogger().log(Level.WARNING, "注销出站通道失败", error);
        }
        Permissions.unregister(this);
        if (storage != null) {
            storage.close();
        }
        if (playbackService != null) {
            playbackService.shutdown();
        }
        if (syncService != null) {
            syncService.clear();
        }
        if (clientRegistry != null) {
            clientRegistry.clear();
        }
        if (tasks != null) {
            tasks.shutdown();
        }
        getLogger().info("MikuVideo 已关闭");
    }

    /** 当前配置快照，运行期只读。 */
    public Settings settings() {
        return settings;
    }

    public ScreenManager screenManager() {
        return screenManager;
    }

    public PlaybackService playbackService() {
        return playbackService;
    }

    public SyncService syncService() {
        return syncService;
    }

    public ClientRegistry clientRegistry() {
        return clientRegistry;
    }

    public MenuService menuService() {
        return menuService;
    }

    /**
     * 重新读取配置并原子替换快照，随后按新参数重启定时任务。
     *
     * @return 是否成功
     */
    public boolean reloadSettings() {
        try {
            reloadConfig();
            settings = Settings.load(this);
            startTimers();
            return true;
        } catch (RuntimeException error) {
            getLogger().log(Level.SEVERE, "配置重载失败，继续使用旧配置", error);
            return false;
        }
    }

    private void enableServices() {
        saveDefaultConfig();
        settings = Settings.load(this);
        tasks = new Tasks(this);
        screenManager = new ScreenManager(this::settings);
        playbackService = new PlaybackService(screenManager, this::settings, tasks);
        storage = new ScreenStorage(this, screenManager, playbackService, this::settings, tasks);
        screenManager.addListener(storage);
        screenManager.addListener(playbackService);

        List<ScreenStorage.Loaded> loaded = storage.load();
        for (ScreenStorage.Loaded entry : loaded) {
            Screen screen = entry.screen();
            screenManager.importScreen(screen);
            playbackService.importState(screen.id(), entry.playback());
            playbackService.restoreQueue(screen.id(), entry.queue());
        }

        clientRegistry = new ClientRegistry();
        syncService = new SyncService(this, screenManager, playbackService, clientRegistry, this::settings, tasks);
        playbackService.setBroadcaster(syncService);
        screenManager.addListener(syncService);
        menuService = new MenuService(screenManager, playbackService, clientRegistry, this::settings, tasks);

        Permissions.register(this);
        packetListener = new PacketListener(this, clientRegistry, syncService, screenManager,
                playbackService, menuService, this::settings, tasks);
        getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, Protocol.CHANNEL, packetListener);

        CommandSupport commandSupport = new CommandSupport(this, screenManager, playbackService,
                syncService, clientRegistry, menuService, tasks);
        commandService = new CommandService(this, commandSupport);
        commandService.register();

        getServer().getPluginManager().registerEvents(new PlayerListener(syncService, clientRegistry, tasks), this);

        startTimers();
        getLogger().info("MikuVideo 已启用：协议版本 " + Protocol.VERSION + "，已载入 " + loaded.size()
                + " 块屏幕，可用命令 /" + CommandService.ROOT + "（别名 " + String.join(", ", CommandService.ALIASES) + "）");
    }

    private void startTimers() {
        stopTimers();
        if (tasks == null) {
            return;
        }
        Settings.SyncSettings sync = settings.sync();
        rescanTask = tasks.globalTimer(syncService::rescanAll,
                sync.rescanIntervalTicks(), sync.rescanIntervalTicks());
        rebroadcastTask = tasks.globalTimer(playbackService::rebroadcast,
                sync.rebroadcastPeriodTicks(), sync.rebroadcastPeriodTicks());
        pingTask = tasks.globalTimer(syncService::pingTick,
                sync.pingPeriodTicks(), sync.pingPeriodTicks());
    }

    private void stopTimers() {
        if (tasks == null) {
            return;
        }
        tasks.cancel(rescanTask);
        tasks.cancel(rebroadcastTask);
        tasks.cancel(pingTask);
        rescanTask = null;
        rebroadcastTask = null;
        pingTask = null;
    }
}
