package dev.junxiex.mikuvideo.protocol;

import java.util.List;

/** 服务端与客户端之间的全部消息类型。 */
public sealed interface Message {
    int op();

    record HelloS2C(int protocolVersion, long serverEpochMillis, int flags, String serverName,
                     String downloadUrl, String serverId) implements Message {
        @Override
        public int op() {
            return Protocol.S2C_HELLO;
        }
    }

    record ScreenSyncS2C(List<ScreenInfo> screens) implements Message {
        public ScreenSyncS2C {
            screens = List.copyOf(screens);
        }

        @Override
        public int op() {
            return Protocol.S2C_SCREEN_SYNC;
        }
    }

    record ScreenUpsertS2C(ScreenInfo screen) implements Message {
        @Override
        public int op() {
            return Protocol.S2C_SCREEN_UPSERT;
        }
    }

    record ScreenRemoveS2C(String screenId) implements Message {
        @Override
        public int op() {
            return Protocol.S2C_SCREEN_REMOVE;
        }
    }

    record PlaybackS2C(String screenId, Playback playback) implements Message {
        @Override
        public int op() {
            return Protocol.S2C_PLAYBACK;
        }
    }

    record NoticeS2C(int level, String message) implements Message {
        @Override
        public int op() {
            return Protocol.S2C_NOTICE;
        }
    }

    record ClearS2C() implements Message {
        @Override
        public int op() {
            return Protocol.S2C_CLEAR;
        }
    }

    record PingS2C(long id) implements Message {
        @Override
        public int op() {
            return Protocol.S2C_PING;
        }
    }

    /**
     * 服务端下发 B 站凭证。
     *
     * <p>只发给有权限且通过风控校验的玩家；{@code serverId} 用于客户端把凭证
     * 绑定到当前服务器，{@code expiresAt} 到达后客户端必须丢弃并重新申请。</p>
     */
    record CredentialS2C(String serverId, String cookie, long issuedAt, long expiresAt) implements Message {
        @Override
        public int op() {
            return Protocol.S2C_CREDENTIAL;
        }
    }

    record HelloC2S(int protocolVersion, String modVersion, long clientEpochMillis) implements Message {
        @Override
        public int op() {
            return Protocol.C2S_HELLO;
        }
    }

    record InteractC2S(String screenId, int action) implements Message {
        @Override
        public int op() {
            return Protocol.C2S_INTERACT;
        }
    }

    record MediaErrorC2S(String screenId, int code, String message) implements Message {
        @Override
        public int op() {
            return Protocol.C2S_MEDIA_ERROR;
        }
    }

    record PongC2S(long id, long clientEpochMillis) implements Message {
        @Override
        public int op() {
            return Protocol.C2S_PONG;
        }
    }

    record DurationC2S(String screenId, String url, long durationMillis) implements Message {
        @Override
        public int op() {
            return Protocol.C2S_DURATION;
        }
    }

    record RequestSyncC2S() implements Message {
        @Override
        public int op() {
            return Protocol.C2S_REQUEST_SYNC;
        }
    }

    record CredentialRequestC2S() implements Message {
        @Override
        public int op() {
            return Protocol.C2S_CREDENTIAL_REQUEST;
        }
    }
}