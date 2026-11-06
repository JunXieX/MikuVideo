package dev.junxiex.mikuvideo.menu;

import dev.junxiex.mikuvideo.config.Settings;
import dev.junxiex.mikuvideo.net.ClientRegistry;
import dev.junxiex.mikuvideo.permission.Permissions;
import dev.junxiex.mikuvideo.playback.PlaybackService;
import dev.junxiex.mikuvideo.protocol.Playback;
import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.screen.Screen;
import dev.junxiex.mikuvideo.screen.ScreenGeometry;
import dev.junxiex.mikuvideo.screen.ScreenManager;
import dev.junxiex.mikuvideo.util.Tasks;
import dev.junxiex.mikuvideo.util.Text;
import dev.junxiex.mikuvideo.util.Urls;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Dialog 菜单。
 *
 * <p>菜单完全无状态：页码与屏幕 ID 直接编进按钮回调，点击时重新渲染整页，
 * 因此玩家下线自动消失，也不存在“菜单已失效”的判空分支。</p>
 *
 * <p>Dialog 回调不保证跑在玩家区域线程，所有世界与玩家状态修改统一交给
 * {@link Tasks#runFor(Player, Runnable)} 投递。</p>
 *
 * @author JunXieX
 */
public final class MenuService {

    /** 列表每页条目数。 */
    public static final int PAGE_SIZE = 8;

    private static final int BUTTON_WIDTH = 150;

    private final ScreenManager screens;
    private final PlaybackService playback;
    private final ClientRegistry clients;
    private final Supplier<Settings> settings;
    private final Tasks tasks;

    public MenuService(ScreenManager screens, PlaybackService playback, ClientRegistry clients,
                       Supplier<Settings> settings, Tasks tasks) {
        this.screens = screens;
        this.playback = playback;
        this.clients = clients;
        this.settings = settings == null ? Settings::defaults : settings;
        this.tasks = tasks;
    }

    // ---------------------------------------------------------------- 入口

    /** 屏幕列表（自己 + 有 manage.others 时的全部）。 */
    public void openList(Player player, int page) {
        tasks.runFor(player, () -> doOpenList(player, page));
    }

    /** 控制页。 */
    public void openControl(Player player, String screenId) {
        tasks.runFor(player, () -> doOpenControl(player, screenId));
    }

    /** 链接输入页。 */
    public void openUrlEditor(Player player, String screenId) {
        tasks.runFor(player, () -> doOpenUrlEditor(player, screenId));
    }

    /** 尺寸输入页。 */
    public void openSizeEditor(Player player, String screenId) {
        tasks.runFor(player, () -> doOpenSizeEditor(player, screenId));
    }

    /** 音量页。 */
    public void openVolumeEditor(Player player, String screenId) {
        tasks.runFor(player, () -> doOpenVolumeEditor(player, screenId));
    }

    /** 删除二次确认。 */
    public void openDeleteConfirm(Player player, String screenId) {
        tasks.runFor(player, () -> doOpenDeleteConfirm(player, screenId));
    }

    /** 管理页：全服屏幕一览。 */
    public void openAdmin(Player player, int page) {
        tasks.runFor(player, () -> doOpenAdmin(player, page));
    }

    // ---------------------------------------------------------------- 列表

    private void doOpenList(Player player, int page) {
        boolean all = player.hasPermission(Permissions.MANAGE_OTHERS);
        List<Screen> list = all ? screens.all() : screens.ownedBy(player.getUniqueId());
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text("可管理屏幕 " + list.size() + " 块，第 "
                        + (current + 1) + " / " + pages + " 页")),
                DialogBody.plainMessage(Component.text("客户端：" + clients.describe(player.getUniqueId()))));
        List<ActionButton> buttons = new ArrayList<>();
        int from = current * PAGE_SIZE;
        int to = Math.min(list.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            Screen screen = list.get(i);
            Component label = Component.text("打开控制 · " + screen.name());
            Component tooltip = Component.text(screen.id() + "　" + screen.world()
                    + "　" + screen.width() + "x" + screen.height()
                    + "　" + (screen.publicScreen() ? "公开" : "私有"));
            buttons.add(button(label, tooltip, click((response, clicker) -> openControl(clicker, screen.id()))));
        }
        if (buttons.isEmpty()) {
            buttons.add(button(Component.text("还没有屏幕"),
                    Component.text("用 /mv create <名称> 看向方块创建一块"),
                    click((response, clicker) -> message(clicker, "<gray>没有可管理的屏幕"))));
        }
        if (current > 0) {
            int target = current - 1;
            buttons.add(button(Component.text("上一页"), Component.text("第 " + (target + 1) + " 页"),
                    click((response, clicker) -> openList(clicker, target))));
        }
        if (current < pages - 1) {
            int target = current + 1;
            buttons.add(button(Component.text("下一页"), Component.text("第 " + (target + 1) + " 页"),
                    click((response, clicker) -> openList(clicker, target))));
        }
        show(player, Component.text("MikuVideo · 屏幕列表"), body, List.of(), buttons, 2);
    }

    // ---------------------------------------------------------------- 控制页

    private void doOpenControl(Player player, String screenId) {
        Screen screen = screens.get(screenId).orElse(null);
        if (screen == null) {
            message(player, settings.get().messages().screenNotFound());
            return;
        }
        if (!canManage(player, screen)) {
            message(player, settings.get().messages().noPermission());
            return;
        }
        Playback state = playback.state(screen.id());
        long position = state.positionAt(System.currentTimeMillis());
        int limit = urlLimit();
        String url = state.url();
        String referer = state.headers().getOrDefault("Referer", "");
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text("屏幕：" + screen.name() + "　ID：" + screen.id())),
                DialogBody.plainMessage(Component.text("位置：" + screen.world() + "　"
                        + Text.number(screen.cx()) + ", " + Text.number(screen.cy()) + ", " + Text.number(screen.cz()))),
                DialogBody.plainMessage(Component.text("链接：" + (state.isStopped() ? "未设置" : url))),
                DialogBody.plainMessage(Component.text("状态：" + (state.paused() ? "暂停" : "播放中")
                        + "　进度：" + Text.duration(position) + " / "
                        + (state.durationMillis() > 0L ? Text.duration(state.durationMillis()) : "未知"))),
                DialogBody.plainMessage(Component.text("尺寸：" + screen.width() + " x " + screen.height()
                        + "　音量：" + screen.volume() + "　可见性：" + (screen.publicScreen() ? "公开" : "私有"))),
                DialogBody.plainMessage(Component.text("客户端：" + clients.describe(player.getUniqueId()))));
        List<DialogInput> inputs = List.of(
                DialogInput.text("url", Component.text("视频链接"))
                        .maxLength(limit)
                        .initial(clampText(url, limit))
                        .build(),
                DialogInput.text("referer", Component.text("Referer（可选）"))
                        .maxLength(limit)
                        .initial(clampText(referer, limit))
                        .build(),
                DialogInput.text("width", Component.text("宽度"))
                        .maxLength(16)
                        .initial(Float.toString(screen.width()))
                        .build(),
                DialogInput.text("height", Component.text("高度"))
                        .maxLength(16)
                        .initial(Float.toString(screen.height()))
                        .build(),
                DialogInput.numberRange("volume", Component.text("音量"), 0.0F, 100.0F)
                        .initial((float) screen.volume())
                        .step(1.0F)
                        .build());
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(Component.text(state.paused() ? "继续播放" : "暂停"),
                Component.text("切换暂停状态"),
                click((response, clicker) -> togglePlayback(clicker, screenId, response))));
        buttons.add(button(Component.text("停止"), Component.text("清空链接与进度"),
                click((response, clicker) -> stopPlayback(clicker, screenId))));
        buttons.add(button(Component.text("设置链接"), Component.text("输入新的播放地址"),
                click((response, clicker) -> openUrlEditor(clicker, screenId))));
        buttons.add(button(Component.text("应用尺寸"), Component.text("使用上方宽高输入"),
                click((response, clicker) -> applySize(clicker, screenId, response))));
        buttons.add(button(Component.text("应用音量"), Component.text("使用上方音量滑块"),
                click((response, clicker) -> applyVolume(clicker, screenId, response))));
        buttons.add(button(Component.text("移动到这里"), Component.text("把屏幕贴到你看向的方块面"),
                click((response, clicker) -> applyMove(clicker, screenId))));
        buttons.add(button(Component.text(screen.publicScreen() ? "设为私有" : "设为公开"),
                Component.text("切换可见性"),
                click((response, clicker) -> applyAccess(clicker, screenId, !screen.publicScreen()))));
        buttons.add(button(Component.text("删除"), Component.text("需要二次确认"),
                click((response, clicker) -> openDeleteConfirm(clicker, screenId))));
        buttons.add(button(Component.text("返回列表"), Component.text("回到屏幕列表"),
                click((response, clicker) -> openList(clicker, 0))));
        show(player, Component.text("MikuVideo · 屏幕控制"), body, inputs, buttons, 3);
    }

    // ---------------------------------------------------------------- 单页输入

    private void doOpenUrlEditor(Player player, String screenId) {
        Screen screen = screens.get(screenId).orElse(null);
        if (screen == null || !canManage(player, screen)) {
            message(player, settings.get().messages().screenNotFound());
            return;
        }
        Playback state = playback.state(screen.id());
        int limit = urlLimit();
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text("当前链接：" + (state.isStopped() ? "未设置" : state.url()))),
                DialogBody.plainMessage(Component.text("只允许 http / https，长度上限 " + limit + " 字符")));
        List<DialogInput> inputs = List.of(
                DialogInput.text("url", Component.text("视频链接"))
                        .maxLength(limit)
                        .initial(clampText(state.url(), limit))
                        .build(),
                DialogInput.text("referer", Component.text("Referer（可选）"))
                        .maxLength(limit)
                        .initial(clampText(state.headers().getOrDefault("Referer", ""), limit))
                        .build());
        List<ActionButton> buttons = List.of(
                button(Component.text("应用"), Component.text("校验并开始播放"),
                        click((response, clicker) -> {
                            Screen target = screens.get(screenId).orElse(null);
                            if (target == null) {
                                message(clicker, settings.get().messages().screenNotFound());
                                return;
                            }
                            String url = text(response, "url");
                            if (applyUrl(clicker, target, url, text(response, "referer"))) {
                                openControl(clicker, screenId);
                            }
                        })),
                button(Component.text("返回控制页"), Component.text("不修改链接"),
                        click((response, clicker) -> openControl(clicker, screenId))));
        show(player, Component.text("MikuVideo · 设置链接"), body, inputs, buttons, 2);
    }

    private void doOpenSizeEditor(Player player, String screenId) {
        Screen screen = screens.get(screenId).orElse(null);
        if (screen == null || !canManage(player, screen)) {
            message(player, settings.get().messages().screenNotFound());
            return;
        }
        Settings.Limits limits = settings.get().limits();
        List<DialogBody> body = List.of(DialogBody.plainMessage(Component.text("宽度 " + limits.minScreenWidth()
                + " ~ " + limits.maxScreenWidth() + "，高度 " + limits.minScreenHeight()
                + " ~ " + limits.maxScreenHeight())));
        List<DialogInput> inputs = List.of(
                DialogInput.text("width", Component.text("宽度")).maxLength(16)
                        .initial(Float.toString(screen.width())).build(),
                DialogInput.text("height", Component.text("高度")).maxLength(16)
                        .initial(Float.toString(screen.height())).build());
        List<ActionButton> buttons = List.of(
                button(Component.text("应用"), Component.text("按输入值调整尺寸"),
                        click((response, clicker) -> applySize(clicker, screenId, response))),
                button(Component.text("返回控制页"), Component.text("不修改尺寸"),
                        click((response, clicker) -> openControl(clicker, screenId))));
        show(player, Component.text("MikuVideo · 屏幕尺寸"), body, inputs, buttons, 2);
    }

    private void doOpenVolumeEditor(Player player, String screenId) {
        Screen screen = screens.get(screenId).orElse(null);
        if (screen == null || !canManage(player, screen)) {
            message(player, settings.get().messages().screenNotFound());
            return;
        }
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text("当前音量：" + screen.volume())));
        List<DialogInput> inputs = List.of(
                DialogInput.numberRange("volume", Component.text("音量"), 0.0F, 100.0F)
                        .initial((float) screen.volume())
                        .step(1.0F)
                        .build());
        List<ActionButton> buttons = List.of(
                button(Component.text("应用"), Component.text("保存音量"),
                        click((response, clicker) -> applyVolume(clicker, screenId, response))),
                button(Component.text("返回控制页"), Component.text("不修改音量"),
                        click((response, clicker) -> openControl(clicker, screenId))));
        show(player, Component.text("MikuVideo · 音量"), body, inputs, buttons, 2);
    }

    private void doOpenDeleteConfirm(Player player, String screenId) {
        Screen screen = screens.get(screenId).orElse(null);
        if (screen == null || !canManage(player, screen)) {
            message(player, settings.get().messages().screenNotFound());
            return;
        }
        ActionButton confirm = button(Component.text("确认删除"), Component.text("删除后无法恢复"),
                click((response, clicker) -> delete(clicker, screenId)));
        ActionButton cancel = button(Component.text("取消"), Component.text("返回控制页"),
                click((response, clicker) -> openControl(clicker, screenId)));
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("MikuVideo · 删除确认"))
                        .externalTitle(Component.text("删除屏幕 " + screen.name()))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "确定删除屏幕 " + screen.name() + "（" + screen.id() + "）吗？"
                                        + "该操作会立刻从所有客户端移除画面，且无法撤销。"))))
                        .inputs(List.of())
                        .build())
                .type(DialogType.confirmation(confirm, cancel)));
        player.showDialog(dialog);
    }

    // ---------------------------------------------------------------- 管理页

    private void doOpenAdmin(Player player, int page) {
        List<Screen> list = screens.all();
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text("全服屏幕 " + list.size() + " 块，第 "
                        + (current + 1) + " / " + pages + " 页")),
                DialogBody.plainMessage(Component.text("在线客户端 " + clients.size() + " 个")));
        List<ActionButton> buttons = new ArrayList<>();
        int from = current * PAGE_SIZE;
        int to = Math.min(list.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            Screen screen = list.get(i);
            buttons.add(button(Component.text("删除 · " + screen.name()),
                    Component.text(screen.id() + "　" + screen.world() + "　归属 "
                            + screen.owner().toString().substring(0, 8)),
                    click((response, clicker) -> delete(clicker, screen.id()))));
        }
        if (buttons.isEmpty()) {
            buttons.add(button(Component.text("全服没有屏幕"), Component.text("无需清理"),
                    click((response, clicker) -> message(clicker, "<gray>全服没有屏幕"))));
        }
        if (current > 0) {
            int target = current - 1;
            buttons.add(button(Component.text("上一页"), Component.text("第 " + (target + 1) + " 页"),
                    click((response, clicker) -> openAdmin(clicker, target))));
        }
        if (current < pages - 1) {
            int target = current + 1;
            buttons.add(button(Component.text("下一页"), Component.text("第 " + (target + 1) + " 页"),
                    click((response, clicker) -> openAdmin(clicker, target))));
        }
        show(player, Component.text("MikuVideo · 管理"), body, List.of(), buttons, 2);
    }

    // ---------------------------------------------------------------- 动作

    private void togglePlayback(Player player, String screenId, DialogResponseView response) {
        Screen screen = screens.get(screenId).orElse(null);
        if (screen == null) {
            message(player, settings.get().messages().screenNotFound());
            return;
        }
        String url = text(response, "url");
        Playback state = playback.state(screen.id());
        if (url != null && !url.isBlank() && !url.equals(state.url())) {
            if (applyUrl(player, screen, url, text(response, "referer"))) {
                openControl(player, screenId);
            }
            return;
        }
        if (state.isStopped()) {
            message(player, "<yellow>该屏幕还没有设置链接");
            return;
        }
        boolean changed = state.paused() ? playback.resume(screen) : playback.pause(screen);
        if (!changed) {
            message(player, "<yellow>操作未生效，请重试");
        }
        openControl(player, screenId);
    }

    private boolean applyUrl(Player player, Screen screen, String url, String referer) {
        Urls.Checked checked = Urls.validate(url, referer, settings.get().media());
        if (!checked.ok()) {
            message(player, "<red>" + checked.message());
            return false;
        }
        playback.play(screen, checked.url(), checked.headers());
        message(player, "<green>已设置播放链接，正在缓冲");
        return true;
    }

    private void stopPlayback(Player player, String screenId) {
        Screen screen = screens.get(screenId).orElse(null);
        if (screen == null) {
            message(player, settings.get().messages().screenNotFound());
            return;
        }
        playback.stop(screen);
        message(player, "<yellow>已停止播放");
        openControl(player, screenId);
    }

    private void applySize(Player player, String screenId, DialogResponseView response) {
        Screen screen = screens.get(screenId).orElse(null);
        if (screen == null) {
            message(player, settings.get().messages().screenNotFound());
            return;
        }
        Double width = parseDouble(text(response, "width"));
        Double height = parseDouble(text(response, "height"));
        if (width == null || height == null) {
            message(player, "<red>宽高必须是数字");
            return;
        }
        Settings.Limits limits = settings.get().limits();
        float clampedWidth = (float) limits.clampWidth(width);
        float clampedHeight = (float) limits.clampHeight(height);
        screens.replace(screen.withSize(clampedWidth, clampedHeight));
        message(player, "<green>尺寸已更新为 " + clampedWidth + " x " + clampedHeight);
        openControl(player, screenId);
    }

    private void applyVolume(Player player, String screenId, DialogResponseView response) {
        Screen screen = screens.get(screenId).orElse(null);
        if (screen == null) {
            message(player, settings.get().messages().screenNotFound());
            return;
        }
        Float volume = null;
        if (response != null) {
            try {
                volume = response.getFloat("volume");
            } catch (RuntimeException ignored) {
                volume = null;
            }
        }
        int value = volume == null ? screen.volume() : Math.round(volume);
        playback.volume(screen, value);
        message(player, "<green>音量已设为 " + Math.max(0, Math.min(100, value)));
        openControl(player, screenId);
    }

    private void applyMove(Player player, String screenId) {
        Screen screen = screens.get(screenId).orElse(null);
        if (screen == null) {
            message(player, settings.get().messages().screenNotFound());
            return;
        }
        Optional<ScreenGeometry.Hit> hit = ScreenGeometry.look(player);
        if (hit.isEmpty()) {
            message(player, "<red>请看向 6 格内的方块表面");
            return;
        }
        screens.replace(screen.withGeometry(hit.get()));
        message(player, "<green>屏幕已移动到当前视线位置");
        openControl(player, screenId);
    }

    private void applyAccess(Player player, String screenId, boolean publicScreen) {
        Screen screen = screens.get(screenId).orElse(null);
        if (screen == null) {
            message(player, settings.get().messages().screenNotFound());
            return;
        }
        screens.replace(screen.withPublic(publicScreen));
        message(player, publicScreen ? "<green>已设为公开" : "<yellow>已设为私有");
        openControl(player, screenId);
    }

    private void delete(Player player, String screenId) {
        Screen screen = screens.get(screenId).orElse(null);
        if (screen == null) {
            message(player, settings.get().messages().screenNotFound());
            return;
        }
        if (!canManage(player, screen)) {
            message(player, settings.get().messages().noPermission());
            return;
        }
        playback.forget(screen.id());
        screens.remove(screen.id());
        message(player, "<green>已删除屏幕 " + screen.name());
        openList(player, 0);
    }

    // ---------------------------------------------------------------- 公共小工具

    private boolean canManage(Player player, Screen screen) {
        if (player == null || screen == null) {
            return false;
        }
        UUID id = player.getUniqueId();
        if (screen.ownedBy(id)) {
            return player.hasPermission(Permissions.MANAGE_OWN);
        }
        return player.hasPermission(Permissions.MANAGE_OTHERS);
    }

    private int urlLimit() {
        return Math.max(64, Math.min(settings.get().media().maxUrlLength(), Protocol.MAX_URL));
    }

    private static String clampText(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String text(DialogResponseView response, String key) {
        if (response == null) {
            return null;
        }
        try {
            return response.getText(key);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private void message(Player player, String body) {
        if (player == null) {
            return;
        }
        player.sendMessage(Text.prefixed(settings.get().messages().prefix(), body));
    }

    private static ActionButton button(Component label, Component tooltip, DialogAction action) {
        return ActionButton.builder(label).tooltip(tooltip).width(BUTTON_WIDTH).action(action).build();
    }

    /**
     * 自定义点击。
     *
     * <p>Dialog 回调不保证在玩家区域线程执行，因此这里统一投递一次，
     * 回调体内部可以直接读写玩家与世界状态。</p>
     */
    private DialogAction click(BiConsumer<DialogResponseView, Player> handler) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player) {
                tasks.runFor(player, () -> handler.accept(response, player));
            }
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build());
    }

    private void show(Player player, Component title, List<DialogBody> body, List<DialogInput> inputs,
                      List<ActionButton> buttons, int columns) {
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .externalTitle(title)
                        .canCloseWithEscape(true)
                        .body(body)
                        .inputs(inputs)
                        .build())
                .type(DialogType.multiAction(buttons).columns(columns).build()));
        player.showDialog(dialog);
    }
}
