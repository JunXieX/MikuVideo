package dev.junxiex.mikuvideo.protocol;

/**
 * 客户端模组与服务端插件共用的协议常量。
 *
 * <p>通道名固定为 {@code mikuvideo:main}，单条消息的第一个字节是操作码。
 * 协议版本不一致时客户端必须拒绝处理消息并在聊天栏提示玩家更新模组。</p>
 */
public final class Protocol {
    private Protocol() {
    }

    public static final String NAMESPACE = "mikuvideo";
    public static final String CHANNEL = "mikuvideo:main";

    /** 协议版本，任何字段变更都要递增。 */
    public static final int VERSION = 1;

    /** 单条插件消息上限（原版 CustomPayload 上限为 1 MiB，这里留出余量）。 */
    public static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    /** 客户端 -> 服务端单包上限（Minecraft 的 ServerboundCustomPayloadPacket 上限 32767 字节）。 */
    public static final int MAX_C2S_MESSAGE_BYTES = 32767;

    public static final int MAX_SCREEN_ID = 32;
    public static final int MAX_SCREEN_NAME = 64;
    public static final int MAX_WORLD_KEY = 128;
    public static final int MAX_URL = 4096;
    public static final int MAX_HEADER_COUNT = 16;
    public static final int MAX_HEADER_KEY = 128;
    public static final int MAX_HEADER_VALUE = 2048;
    public static final int MAX_NOTICE = 1024;
    public static final int MAX_MOD_VERSION = 32;
    public static final int MAX_ERROR_MESSAGE = 512;

    /** 屏幕上限，防止恶意客户端把列表撑爆。 */
    public static final int MAX_SCREEN_COUNT = 512;

    // 服务端 -> 客户端
    public static final int S2C_HELLO = 1;
    public static final int S2C_SCREEN_SYNC = 2;
    public static final int S2C_SCREEN_UPSERT = 3;
    public static final int S2C_SCREEN_REMOVE = 4;
    public static final int S2C_PLAYBACK = 5;
    public static final int S2C_NOTICE = 6;
    public static final int S2C_CLEAR = 7;
    public static final int S2C_PING = 8;

    // 客户端 -> 服务端
    public static final int C2S_HELLO = 101;
    public static final int C2S_INTERACT = 102;
    public static final int C2S_MEDIA_ERROR = 103;
    public static final int C2S_PONG = 104;
    public static final int C2S_DURATION = 105;
    public static final int C2S_REQUEST_SYNC = 106;

    // C2S_INTERACT 的动作
    public static final int INTERACT_OPEN_MENU = 0;

    // 提示级别
    public static final int NOTICE_INFO = 0;
    public static final int NOTICE_WARN = 1;
    public static final int NOTICE_ERROR = 2;

    // HELLO 标记位
    public static final int HELLO_FLAG_REQUIRE_MOD = 1;
}