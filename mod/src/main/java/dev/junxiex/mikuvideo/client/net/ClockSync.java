package dev.junxiex.mikuvideo.client.net;

/** 客户端与服务端时钟偏差估计。 */
public final class ClockSync {
    private static volatile long offsetMillis;

    private ClockSync() {
    }

    public static void updateFromHello(long serverEpochMillis) {
        offsetMillis = serverEpochMillis - System.currentTimeMillis();
    }

    public static long serverNow() {
        return System.currentTimeMillis() + offsetMillis;
    }

    public static void reset() {
        offsetMillis = 0L;
    }
}
