package dev.junxiex.mikuvideo.permission;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限节点。
 *
 * <p>全部在 {@code onEnable} 运行期注册、{@code onDisable} 注销，
 * 不写进 {@code paper-plugin.yml}，避免 reload 之后残留陈旧节点。</p>
 *
 * @author JunXieX
 */
public final class Permissions {

    public static final String USE = "mikuvideo.use";
    public static final String CREATE = "mikuvideo.create";
    public static final String MANAGE_OWN = "mikuvideo.manage.own";
    public static final String MANAGE_OTHERS = "mikuvideo.manage.others";
    public static final String ADMIN = "mikuvideo.admin";
    public static final String LIMIT_UNLIMITED = "mikuvideo.limit.unlimited";

    private record Node(String name, String description, PermissionDefault defaultValue) {
    }

    private static final List<Node> NODES = List.of(
            new Node(USE, "允许使用 MikuVideo 命令", PermissionDefault.TRUE),
            new Node(CREATE, "允许创建屏幕", PermissionDefault.TRUE),
            new Node(MANAGE_OWN, "允许管理自己的屏幕", PermissionDefault.TRUE),
            new Node(MANAGE_OTHERS, "允许管理其他玩家的屏幕", PermissionDefault.OP),
            new Node(ADMIN, "允许使用管理子命令", PermissionDefault.OP),
            new Node(LIMIT_UNLIMITED, "不受单玩家屏幕数量限制", PermissionDefault.OP));

    private static final Set<String> extraRegistered = ConcurrentHashMap.newKeySet();

    private Permissions() {
    }

    /** 注册全部节点。 */
    public static void register(Plugin plugin) {
        register(plugin, List.of());
    }

    /** 注册固定节点 + 配置里声明的等级节点（默认 false）。 */
    public static void register(Plugin plugin, Collection<String> extra) {
        if (plugin == null) {
            return;
        }
        PluginManager manager = plugin.getServer().getPluginManager();
        for (Node node : NODES) {
            if (manager.getPermission(node.name()) != null) {
                continue;
            }
            manager.addPermission(new Permission(node.name(), node.description(), node.defaultValue()));
        }
        if (extra != null) {
            for (String name : extra) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                String trimmed = name.trim();
                if (manager.getPermission(trimmed) != null) {
                    continue;
                }
                manager.addPermission(new Permission(trimmed, "MikuVideo 屏幕等级", PermissionDefault.FALSE));
                extraRegistered.add(trimmed);
            }
        }
    }

    /** 注销本插件注册的节点。 */
    public static void unregister(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        PluginManager manager = plugin.getServer().getPluginManager();
        for (Node node : NODES) {
            manager.removePermission(node.name());
        }
        for (String name : extraRegistered) {
            manager.removePermission(name);
        }
        extraRegistered.clear();
    }

}
