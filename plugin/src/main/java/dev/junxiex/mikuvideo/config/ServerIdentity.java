package dev.junxiex.mikuvideo.config;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 服务器身份。
 *
 * <p>首次启动生成一个 32 位十六进制 ID 并写入 {@code server-id.txt}。
 * 客户端用它把服务端下发的 B 站凭证绑定到本服，换服或复制配置文件都无法复用。</p>
 *
 * @author JunXieX
 */
public final class ServerIdentity {
    private static final Pattern VALID = Pattern.compile("[0-9a-f]{32}");

    private final String id;

    public ServerIdentity(Plugin plugin) {
        String value = "";
        try {
            value = loadOrCreate(plugin.getDataFolder().toPath().resolve("server-id.txt"));
        } catch (IOException error) {
            plugin.getLogger().warning("读写 server-id.txt 失败，本次使用临时身份: " + error.getMessage());
        }
        if (!VALID.matcher(value).matches()) {
            value = UUID.randomUUID().toString().replace("-", "");
        }
        this.id = value;
    }

    public String id() {
        return id;
    }

    private static String loadOrCreate(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8).trim().toLowerCase();
            if (VALID.matcher(existing).matches()) {
                return existing;
            }
        }
        String generated = UUID.randomUUID().toString().replace("-", "");
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, generated + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return generated;
    }
}
