package dev.junxiex.mikuvideo.client.credential;

import dev.junxiex.mikuvideo.client.MikuVideoClient;
import dev.junxiex.mikuvideo.client.net.ClientNetworking;
import dev.junxiex.mikuvideo.protocol.Message;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 服务端 B 站凭证的客户端生命周期。
 *
 * <p>凭证只在「当前服务器 ID + 服务器地址哈希」同时匹配时启用；
 * 断开连接立即从内存清除。落盘由 {@link ServerCredentialStore} 做 AES-GCM 加密。</p>
 *
 * @author JunXieX
 */
public final class CredentialService {
    private final ServerCredentialStore store;
    private volatile String serverId = "";
    private volatile String serverHash = "";
    private volatile String activeCookie = "";
    private volatile long expiresAt;
    private boolean announced;

    public CredentialService(Path gameDir) {
        Path file = gameDir.resolve("config").resolve("mikuvideo-credentials.json");
        this.store = new ServerCredentialStore(file, fingerprint(gameDir));
    }

    /** 收到 HELLO 后绑定服务器并尝试复用已保存的凭证。 */
    public void onHello(String id) {
        this.serverId = id == null ? "" : id;
        this.serverHash = currentServerHash();
        this.activeCookie = "";
        this.expiresAt = 0L;
        MikuVideoClient.media().resolver().setServerCookie("");
        if (!enabled() || serverId.isBlank() || serverHash.isBlank()) {
            return;
        }
        String cookie = store.get(serverId, serverHash);
        if (cookie != null && !cookie.isBlank()) {
            activeCookie = cookie;
            MikuVideoClient.media().resolver().setServerCookie(cookie);
            MikuVideoClient.LOGGER.info("已加载本服 B 站凭证（加密存储）");
        } else if (store.has(serverHash)) {
            // 之前拿到过但已过期：自动续期一次，失败时由服务端频率限制兜底
            requestRefresh(true);
        }
    }

    /** 收到服务端凭证。 */
    public void onCredential(Message.CredentialS2C credential) {
        if (credential == null || !enabled()) {
            return;
        }
        if (serverId.isBlank() || !serverId.equals(credential.serverId()) || serverHash.isBlank()) {
            return;
        }
        if (credential.cookie().isBlank() || credential.expiresAt() <= System.currentTimeMillis()) {
            return;
        }
        store.put(credential.serverId(), serverHash, credential.cookie(), credential.expiresAt());
        activeCookie = credential.cookie();
        expiresAt = credential.expiresAt();
        MikuVideoClient.media().resolver().setServerCookie(activeCookie);
        if (!announced) {
            announced = true;
            MikuVideoClient.chat("已获取服务器 B 站凭证（加密保存在本机，仅本服可用）");
        }
    }

    /** 配置重载后调用：关闭开关时立即清掉内存中的凭证。 */
    public void onConfigReload() {
        if (!enabled()) {
            activeCookie = "";
            expiresAt = 0L;
            MikuVideoClient.media().resolver().setServerCookie("");
        }
    }

    public void onDisconnect() {
        activeCookie = "";
        expiresAt = 0L;
        serverId = "";
        serverHash = "";
        announced = false;
        MikuVideoClient.media().resolver().setServerCookie("");
    }

    /** 当前可用凭证，过期后自动返回空。 */
    public String activeCookie() {
        if (activeCookie.isBlank()) {
            return "";
        }
        if (expiresAt > 0L && expiresAt <= System.currentTimeMillis()) {
            activeCookie = "";
            MikuVideoClient.media().resolver().setServerCookie("");
            return "";
        }
        return activeCookie;
    }

    public void requestRefresh() {
        requestRefresh(false);
    }

    private void requestRefresh(boolean silent) {
        if (!enabled()) {
            if (!silent) {
                MikuVideoClient.chat("客户端配置已关闭服务端凭证（acceptServerCookie=false）");
            }
            return;
        }
        if (serverId.isBlank()) {
            if (!silent) {
                MikuVideoClient.chat("尚未与服务器完成握手");
            }
            return;
        }
        ClientNetworking.send(new Message.CredentialRequestC2S());
        if (!silent) {
            MikuVideoClient.chat("已请求服务器下发 B 站凭证");
        }
    }

    public void clear() {
        if (!serverHash.isBlank()) {
            store.remove(serverHash);
        }
        activeCookie = "";
        expiresAt = 0L;
        MikuVideoClient.media().resolver().setServerCookie("");
    }

    public String statusText() {
        if (!enabled()) {
            return "服务端凭证：客户端已关闭";
        }
        if (serverId.isBlank()) {
            return "服务端凭证：尚未连接服务器";
        }
        String state = activeCookie().isBlank() ? "未获取" : "已生效";
        String expiry = expiresAt <= 0L ? "未知" : java.time.Instant.ofEpochMilli(expiresAt).toString();
        return "服务端凭证：" + state + "，绑定服务器 " + shortId(serverId)
                + "，过期时间 " + expiry + "，本地记录 " + store.describe(serverHash);
    }

    private boolean enabled() {
        return MikuVideoClient.config().acceptServerCookie;
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String currentServerHash() {
        ServerData data = Minecraft.getInstance().getCurrentServer();
        if (data == null || data.ip == null || data.ip.isBlank()) {
            return "";
        }
        return sha256(data.ip.trim().toLowerCase(Locale.ROOT));
    }

    private static String fingerprint(Path gameDir) {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception error) {
            host = "unknown";
        }
        return System.getProperty("os.name", "") + "|"
                + System.getProperty("os.arch", "") + "|"
                + System.getProperty("user.name", "") + "|"
                + host + "|"
                + gameDir.toAbsolutePath();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception error) {
            return "";
        }
    }
}
