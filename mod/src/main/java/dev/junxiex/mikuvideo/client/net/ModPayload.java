package dev.junxiex.mikuvideo.client.net;

import dev.junxiex.mikuvideo.protocol.Protocol;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 承载 MikuVideo 协议字节的单一通道载荷。 */
public record ModPayload(byte[] data) implements CustomPacketPayload {
    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath(Protocol.NAMESPACE, "main");
    public static final CustomPacketPayload.Type<ModPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);

    public static final StreamCodec<RegistryFriendlyByteBuf, ModPayload> CODEC = StreamCodec.ofMember(ModPayload::write, ModPayload::read);

    private static void write(ModPayload payload, RegistryFriendlyByteBuf buffer) {
        buffer.writeBytes(payload.data);
    }

    private static ModPayload read(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readableBytes();
        if (size <= 0 || size > Protocol.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("非法的 MikuVideo 载荷长度: " + size);
        }
        byte[] data = new byte[size];
        buffer.readBytes(data);
        return new ModPayload(data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
