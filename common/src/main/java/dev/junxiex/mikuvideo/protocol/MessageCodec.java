package dev.junxiex.mikuvideo.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 二进制编解码。所有长度都做上限校验，避免恶意包造成内存膨胀。 */
public final class MessageCodec {
    private MessageCodec() {
    }

    public static byte[] encode(Message message) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(message.op());
            switch (message) {
                case Message.HelloS2C hello -> {
                    out.writeInt(hello.protocolVersion());
                    out.writeLong(hello.serverEpochMillis());
                    out.writeInt(hello.flags());
                    writeString(out, hello.serverName(), 128);
                    writeString(out, hello.downloadUrl(), Protocol.MAX_URL);
                    writeString(out, hello.serverId(), Protocol.MAX_SERVER_ID);
                }
                case Message.ScreenSyncS2C sync -> {
                    out.writeInt(sync.screens().size());
                    for (ScreenInfo screen : sync.screens()) {
                        writeScreen(out, screen);
                    }
                }
                case Message.ScreenUpsertS2C upsert -> writeScreen(out, upsert.screen());
                case Message.ScreenRemoveS2C remove -> writeString(out, remove.screenId(), Protocol.MAX_SCREEN_ID);
                case Message.PlaybackS2C playback -> {
                    writeString(out, playback.screenId(), Protocol.MAX_SCREEN_ID);
                    writePlayback(out, playback.playback());
                }
                case Message.NoticeS2C notice -> {
                    out.writeByte(notice.level());
                    writeString(out, notice.message(), Protocol.MAX_NOTICE);
                }
                case Message.ClearS2C ignored -> {
                }
                case Message.PingS2C ping -> out.writeLong(ping.id());
                case Message.CredentialS2C credential -> {
                    writeString(out, credential.serverId(), Protocol.MAX_SERVER_ID);
                    writeString(out, credential.cookie(), Protocol.MAX_COOKIE);
                    out.writeLong(credential.issuedAt());
                    out.writeLong(credential.expiresAt());
                }
                case Message.HelloC2S hello -> {
                    out.writeInt(hello.protocolVersion());
                    writeString(out, hello.modVersion(), Protocol.MAX_MOD_VERSION);
                    out.writeLong(hello.clientEpochMillis());
                }
                case Message.InteractC2S interact -> {
                    writeString(out, interact.screenId(), Protocol.MAX_SCREEN_ID);
                    out.writeByte(interact.action());
                }
                case Message.MediaErrorC2S error -> {
                    writeString(out, error.screenId(), Protocol.MAX_SCREEN_ID);
                    out.writeInt(error.code());
                    writeString(out, error.message(), Protocol.MAX_ERROR_MESSAGE);
                }
                case Message.PongC2S pong -> {
                    out.writeLong(pong.id());
                    out.writeLong(pong.clientEpochMillis());
                }
                case Message.DurationC2S duration -> {
                    writeString(out, duration.screenId(), Protocol.MAX_SCREEN_ID);
                    out.writeLong(duration.durationMillis());
                }
                case Message.RequestSyncC2S ignored -> {
                }
                case Message.CredentialRequestC2S ignored -> {
                }
            }
        } catch (IOException e) {
            throw new ProtocolException("编码消息失败: " + e.getMessage(), e);
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > Protocol.MAX_MESSAGE_BYTES) {
            throw new ProtocolException("消息超过上限: " + bytes.length);
        }
        return bytes;
    }

    public static Message decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ProtocolException("空消息");
        }
        if (bytes.length > Protocol.MAX_MESSAGE_BYTES) {
            throw new ProtocolException("消息超过上限: " + bytes.length);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int op = in.readUnsignedByte();
            Message message = switch (op) {
                case Protocol.S2C_HELLO -> new Message.HelloS2C(
                        in.readInt(),
                        in.readLong(),
                        in.readInt(),
                        readString(in, 128),
                        readString(in, Protocol.MAX_URL),
                        readString(in, Protocol.MAX_SERVER_ID));
                case Protocol.S2C_SCREEN_SYNC -> {
                    int count = readCount(in, Protocol.MAX_SCREEN_COUNT);
                    List<ScreenInfo> screens = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        screens.add(readScreen(in));
                    }
                    yield new Message.ScreenSyncS2C(screens);
                }
                case Protocol.S2C_SCREEN_UPSERT -> new Message.ScreenUpsertS2C(readScreen(in));
                case Protocol.S2C_SCREEN_REMOVE -> new Message.ScreenRemoveS2C(readString(in, Protocol.MAX_SCREEN_ID));
                case Protocol.S2C_PLAYBACK -> new Message.PlaybackS2C(
                        readString(in, Protocol.MAX_SCREEN_ID),
                        readPlayback(in));
                case Protocol.S2C_NOTICE -> new Message.NoticeS2C(in.readUnsignedByte(), readString(in, Protocol.MAX_NOTICE));
                case Protocol.S2C_CLEAR -> new Message.ClearS2C();
                case Protocol.S2C_PING -> new Message.PingS2C(in.readLong());
                case Protocol.S2C_CREDENTIAL -> new Message.CredentialS2C(
                        readString(in, Protocol.MAX_SERVER_ID),
                        readString(in, Protocol.MAX_COOKIE),
                        in.readLong(),
                        in.readLong());
                case Protocol.C2S_HELLO -> new Message.HelloC2S(
                        in.readInt(),
                        readString(in, Protocol.MAX_MOD_VERSION),
                        in.readLong());
                case Protocol.C2S_INTERACT -> new Message.InteractC2S(
                        readString(in, Protocol.MAX_SCREEN_ID),
                        in.readUnsignedByte());
                case Protocol.C2S_MEDIA_ERROR -> new Message.MediaErrorC2S(
                        readString(in, Protocol.MAX_SCREEN_ID),
                        in.readInt(),
                        readString(in, Protocol.MAX_ERROR_MESSAGE));
                case Protocol.C2S_PONG -> new Message.PongC2S(in.readLong(), in.readLong());
                case Protocol.C2S_DURATION -> new Message.DurationC2S(readString(in, Protocol.MAX_SCREEN_ID), in.readLong());
                case Protocol.C2S_REQUEST_SYNC -> new Message.RequestSyncC2S();
                case Protocol.C2S_CREDENTIAL_REQUEST -> new Message.CredentialRequestC2S();
                default -> throw new ProtocolException("未知操作码: " + op);
            };
            if (in.available() > 0) {
                // 多余字节通常意味着两端协议不一致，直接拒绝，避免静默错位。
                throw new ProtocolException("消息尾部存在多余字节: " + in.available());
            }
            return message;
        } catch (EOFException e) {
            throw new ProtocolException("消息被截断", e);
        } catch (IOException e) {
            throw new ProtocolException("解码消息失败: " + e.getMessage(), e);
        }
    }

    private static void writeScreen(DataOutputStream out, ScreenInfo screen) throws IOException {
        writeString(out, screen.id(), Protocol.MAX_SCREEN_ID);
        writeString(out, screen.name(), Protocol.MAX_SCREEN_NAME);
        out.writeLong(screen.owner().getMostSignificantBits());
        out.writeLong(screen.owner().getLeastSignificantBits());
        writeString(out, screen.world(), Protocol.MAX_WORLD_KEY);
        out.writeDouble(screen.x());
        out.writeDouble(screen.y());
        out.writeDouble(screen.z());
        out.writeFloat(screen.nx());
        out.writeFloat(screen.ny());
        out.writeFloat(screen.nz());
        out.writeFloat(screen.rx());
        out.writeFloat(screen.ry());
        out.writeFloat(screen.rz());
        out.writeFloat(screen.ux());
        out.writeFloat(screen.uy());
        out.writeFloat(screen.uz());
        out.writeFloat(screen.width());
        out.writeFloat(screen.height());
        out.writeByte(screen.volume());
        out.writeBoolean(screen.publicScreen());
        out.writeLong(screen.createdAt());
    }

    private static ScreenInfo readScreen(DataInputStream in) throws IOException {
        String id = readString(in, Protocol.MAX_SCREEN_ID);
        String name = readString(in, Protocol.MAX_SCREEN_NAME);
        UUID owner = new UUID(in.readLong(), in.readLong());
        String world = readString(in, Protocol.MAX_WORLD_KEY);
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        float nx = in.readFloat();
        float ny = in.readFloat();
        float nz = in.readFloat();
        float rx = in.readFloat();
        float ry = in.readFloat();
        float rz = in.readFloat();
        float ux = in.readFloat();
        float uy = in.readFloat();
        float uz = in.readFloat();
        float width = in.readFloat();
        float height = in.readFloat();
        int volume = in.readUnsignedByte();
        boolean publicScreen = in.readBoolean();
        long createdAt = in.readLong();
        return new ScreenInfo(id, name, owner, world, x, y, z,
                nx, ny, nz, rx, ry, rz, ux, uy, uz,
                width, height, volume, publicScreen, createdAt);
    }

    private static void writePlayback(DataOutputStream out, Playback playback) throws IOException {
        writeString(out, playback.url(), Protocol.MAX_URL);
        Map<String, String> headers = playback.headers();
        out.writeInt(headers.size());
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            writeString(out, entry.getKey(), Protocol.MAX_HEADER_KEY);
            writeString(out, entry.getValue(), Protocol.MAX_HEADER_VALUE);
        }
        out.writeLong(playback.positionMillis());
        out.writeLong(playback.startEpochMillis());
        out.writeBoolean(playback.paused());
        out.writeFloat(playback.rate());
        out.writeByte(playback.volume());
        out.writeBoolean(playback.audioEnabled());
        out.writeLong(playback.durationMillis());
    }

    private static Playback readPlayback(DataInputStream in) throws IOException {
        String url = readString(in, Protocol.MAX_URL);
        int headerCount = readCount(in, Protocol.MAX_HEADER_COUNT);
        Map<String, String> headers = new LinkedHashMap<>(Math.max(1, headerCount));
        for (int i = 0; i < headerCount; i++) {
            headers.put(readString(in, Protocol.MAX_HEADER_KEY), readString(in, Protocol.MAX_HEADER_VALUE));
        }
        return new Playback(url, headers, in.readLong(), in.readLong(),
                in.readBoolean(), in.readFloat(), in.readUnsignedByte(), in.readBoolean(), in.readLong());
    }

    private static void writeString(DataOutputStream out, String value, int maxBytes) throws IOException {
        String safe = value == null ? "" : value;
        byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new ProtocolException("字符串超过上限 " + maxBytes + ": " + bytes.length);
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readUnsignedShort();
        if (length > maxBytes) {
            throw new ProtocolException("字符串长度非法: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readCount(DataInputStream in, int max) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) {
            throw new ProtocolException("数量非法: " + count);
        }
        return count;
    }
}