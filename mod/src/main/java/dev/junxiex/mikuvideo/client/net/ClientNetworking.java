package dev.junxiex.mikuvideo.client.net;

import dev.junxiex.mikuvideo.client.MikuVideoClient;
import dev.junxiex.mikuvideo.protocol.Message;
import dev.junxiex.mikuvideo.protocol.MessageCodec;
import dev.junxiex.mikuvideo.protocol.Protocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** 客户端插件消息通道。 */
public final class ClientNetworking {
    private static boolean helloPending = true;
    private static long lastHelloAt;
    private static int helloAttempts;
    private static String lastDimension;

    private ClientNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ModPayload.TYPE, ModPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayload.TYPE, ModPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ModPayload.TYPE, (payload, context) ->
                context.client().execute(() -> receive(payload)));
    }

    public static void onJoin() {
        helloPending = true;
        lastHelloAt = 0L;
        helloAttempts = 0;
        lastDimension = null;
        sendHello();
    }

    public static void onServerHello() {
        helloPending = false;
    }

    public static void stopHello() {
        helloPending = false;
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        String dimension = minecraft.level.dimension().identifier().toString();
        if (lastDimension != null && !lastDimension.equals(dimension)) {
            send(new Message.RequestSyncC2S());
        }
        lastDimension = dimension;
        if (helloPending && helloAttempts < 30 && System.currentTimeMillis() - lastHelloAt > 1_000L) {
            sendHello();
        }
    }

    private static void sendHello() {
        // 先推进计数再判通道：服务端没装插件时 canSend 恒为 false，
        // 不推进就会每 tick 重试到玩家退出世界。
        lastHelloAt = System.currentTimeMillis();
        helloAttempts++;
        if (!ClientPlayNetworking.canSend(ModPayload.TYPE)) {
            return;
        }
        send(new Message.HelloC2S(Protocol.VERSION, modVersion(), lastHelloAt));
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer("mikuvideo")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public static void send(Message message) {
        try {
            if (!ClientPlayNetworking.canSend(ModPayload.TYPE)) {
                return;
            }
            byte[] encoded = MessageCodec.encode(message);
            if (encoded.length > Protocol.MAX_C2S_MESSAGE_BYTES) {
                MikuVideoClient.LOGGER.warn("客户端消息超过服务端单包上限: {} 字节", encoded.length);
                return;
            }
            ClientPlayNetworking.send(new ModPayload(encoded));
        } catch (RuntimeException e) {
            MikuVideoClient.LOGGER.warn("发送 MikuVideo 消息失败: {}", e.getMessage());
        }
    }

    private static void receive(ModPayload payload) {
        try {
            MikuVideoClient.handle(MessageCodec.decode(payload.data()));
        } catch (RuntimeException e) {
            MikuVideoClient.LOGGER.warn("处理 MikuVideo 消息失败: {}", e.getMessage());
        }
    }
}