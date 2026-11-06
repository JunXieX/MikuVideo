package dev.junxiex.mikuvideo.client.credential;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 服务端凭证的本地加密存储。
 *
 * <p>Cookie 使用 AES-256-GCM 加密，密钥由「机器指纹 + 随机盐」经 PBKDF2 派生；
 * 每条记录同时绑定服务器 ID 与服务器地址哈希，换机器、换服务器都无法解密复用。</p>
 *
 * @author JunXieX
 */
public final class ServerCredentialStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int SALT_BYTES = 16;

    private final Path file;
    private final String fingerprint;
    private final SecureRandom random = new SecureRandom();
    private final List<Entry> entries = new ArrayList<>();

    public ServerCredentialStore(Path file, String fingerprint) {
        this.file = file;
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        load();
    }

    public synchronized void put(String serverId, String serverHash, String cookie, long expiresAt) {
        if (serverId == null || serverId.isBlank() || serverHash == null || serverHash.isBlank()
                || cookie == null || cookie.isBlank()) {
            return;
        }
        try {
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(salt);
            random.nextBytes(iv);
            SecretKey key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD((serverId + "|" + serverHash).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(cookie.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            Entry entry = new Entry();
            entry.serverId = serverId;
            entry.serverHash = serverHash;
            entry.updatedAt = System.currentTimeMillis();
            entry.expiresAt = expiresAt;
            entry.salt = encoder.encodeToString(salt);
            entry.iv = encoder.encodeToString(iv);
            entry.data = encoder.encodeToString(encrypted);
            entries.removeIf(existing -> existing.serverHash.equals(serverHash));
            entries.add(entry);
            save();
        } catch (Exception error) {
            // 加密失败时宁可不保存，也不把明文写进磁盘
        }
    }

    /** 读取有效凭证；服务器 ID 或地址哈希不匹配时返回 {@code null}。 */
    public synchronized String get(String serverId, String serverHash) {
        Entry entry = find(serverHash);
        if (entry == null || !entry.serverId.equals(serverId)) {
            return null;
        }
        if (entry.expiresAt > 0L && entry.expiresAt <= System.currentTimeMillis()) {
            entries.remove(entry);
            save();
            return null;
        }
        try {
            SecretKey key = deriveKey(Base64.getDecoder().decode(entry.salt));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, Base64.getDecoder().decode(entry.iv)));
            cipher.updateAAD((entry.serverId + "|" + entry.serverHash).getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(entry.data));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception error) {
            // 机器指纹或服务器绑定不匹配时解密必然失败，直接删除损坏记录
            entries.remove(entry);
            save();
            return null;
        }
    }

    public synchronized boolean has(String serverHash) {
        Entry entry = find(serverHash);
        return entry != null && (entry.expiresAt <= 0L || entry.expiresAt > System.currentTimeMillis());
    }

    public synchronized void remove(String serverHash) {
        if (entries.removeIf(entry -> entry.serverHash.equals(serverHash))) {
            save();
        }
    }

    public synchronized String describe(String serverHash) {
        Entry entry = find(serverHash);
        if (entry == null) {
            return "无";
        }
        long remaining = entry.expiresAt <= 0L ? 0L : entry.expiresAt - System.currentTimeMillis();
        return "已保存（更新于 " + entry.updatedAt + "，剩余 " + Math.max(0L, remaining / 60000L) + " 分钟）";
    }

    private Entry find(String serverHash) {
        if (serverHash == null || serverHash.isBlank()) {
            return null;
        }
        for (Entry entry : entries) {
            if (entry.serverHash.equals(serverHash)) {
                return entry;
            }
        }
        return null;
    }

    private SecretKey deriveKey(byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(fingerprint.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonArray array = parsed.getAsJsonObject().getAsJsonArray("entries");
            if (array == null) {
                return;
            }
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                Entry entry = GSON.fromJson(element, Entry.class);
                if (entry != null && entry.serverId != null && entry.serverHash != null
                        && entry.salt != null && entry.iv != null && entry.data != null) {
                    entries.add(entry);
                }
            }
        } catch (RuntimeException | IOException ignored) {
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonArray array = new JsonArray();
            for (Entry entry : entries) {
                array.add(GSON.toJsonTree(entry));
            }
            root.add("entries", array);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions();
        } catch (IOException ignored) {
        }
    }

    private void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (RuntimeException | IOException ignored) {
        }
    }

    private static final class Entry {
        private String serverId;
        private String serverHash;
        private long updatedAt;
        private long expiresAt;
        private String salt;
        private String iv;
        private String data;
    }
}
