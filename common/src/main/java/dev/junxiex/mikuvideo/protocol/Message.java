package dev.junxiex.mikuvideo.protocol;

import java.util.List;

/** 服务端与客户端之间的全部消息类型。 */
public sealed interface Message {
    int op();

    record HelloS2C(int protocolVersion, long serverEpochMillis, int flags, String serverName, String downloadUrl)
            implements Message {
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

    record DurationC2S(String screenId, long durationMillis) implements Message {
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
}