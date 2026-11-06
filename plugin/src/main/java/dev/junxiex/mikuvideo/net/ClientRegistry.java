package dev.junxiex.mikuvideo.net;

import dev.junxiex.mikuvideo.protocol.Message;
import dev.junxiex.mikuvideo.protocol.Protocol;
import dev.junxiex.mikuvideo.util.Text;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已握手客户端的登记表。
 *
 * <p>只记录“这个玩家装了模组、协议版本是什么、最近一次心跳”，
 * 任何超时都只做标记，不踢人。</p>
 *
 * @author JunXieX
 */
public final class ClientRegistry {

    /** 超过这个时间没有 PONG 就标记为心跳超时。 */
    public static final long PONG_TIMEOUT_MILLIS = 60_000L;

    /** 单个客户端的状态快照。 */
    public record Client(UUID playerId, String name, int protocolVersion, String modVersion,
                         long handshakedAt, long lastPongAt, boolean overdue) {

        public Client withPong(long now) {
            return new Client(playerId, name, protocolVersion, modVersion, handshakedAt, now, false);
        }

        public Client withOverdue(boolean value) {
            return new Client(playerId, name, protocolVersion, modVersion, handshakedAt, lastPongAt, value);
        }

        /** 用于超时判断的参考时刻：握手时间与最后一次心跳取较晚者。 */
        public long lastSeen() {
            return Math.max(handshakedAt, lastPongAt);
        }
    }

    /** 握手结果，失败时带可读原因。 */
    public record Handshake(Client client, String error) {
        public boolean ok() {
            return client != null;
        }
    }

    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();

    /** 处理客户端 HELLO。 */
    public Handshake handshake(Player player, Message.HelloC2S hello) {
        if (player == null || hello == null) {
            return new Handshake(null, "握手包无效");
        }
        if (hello.protocolVersion() != Protocol.VERSION) {
            return new Handshake(null, "协议版本不匹配（客户端 " + hello.protocolVersion()
                    + "，服务端 " + Protocol.VERSION + "）");
        }
        String modVersion = Text.trimBytes(hello.modVersion(), Protocol.MAX_MOD_VERSION);
        long now = System.currentTimeMillis();
        Client client = new Client(player.getUniqueId(), player.getName(), hello.protocolVersion(),
                modVersion, now, 0L, false);
        clients.put(player.getUniqueId(), client);
        return new Handshake(client, "");
    }

    /** 记录心跳回包。 */
    public void pong(UUID playerId, long now) {
        if (playerId == null) {
            return;
        }
        clients.computeIfPresent(playerId, (key, client) -> client.withPong(now));
    }

    /** 标记心跳超时。 */
    public void markOverdue(UUID playerId) {
        if (playerId == null) {
            return;
        }
        clients.computeIfPresent(playerId, (key, client) -> client.withOverdue(true));
    }

    public boolean isRegistered(UUID playerId) {
        return playerId != null && clients.containsKey(playerId);
    }

    public Optional<Client> client(UUID playerId) {
        return playerId == null ? Optional.empty() : Optional.ofNullable(clients.get(playerId));
    }

    /** 客户端版本的可读描述，未握手时返回提示文案。 */
    public String describe(UUID playerId) {
        Client client = playerId == null ? null : clients.get(playerId);
        if (client == null) {
            return "未安装模组";
        }
        String version = client.modVersion().isEmpty() ? "未知版本" : client.modVersion();
        return version + " / 协议 " + client.protocolVersion() + (client.overdue() ? "（心跳超时）" : "");
    }

    public void remove(UUID playerId) {
        if (playerId != null) {
            clients.remove(playerId);
        }
    }

    public Collection<Client> snapshot() {
        return List.copyOf(clients.values());
    }

    public int size() {
        return clients.size();
    }

    public void clear() {
        clients.clear();
    }
}
