package dev.junxiex.mikuvideo.client.net;

/** 客户端与服务端时钟偏差估计。 */
public final class ClockSync {
    private static volatile long offsetMillis;
    private static volatile long lastRttMillis = -1L;

    private ClockSync() {
    }

    public static void updateFromHello(long serverEpochMillis) {
        offsetMillis = serverEpochMillis - System.currentTimeMillis();
    }

    public static void updateFromPong(long sentAtMillis, long receivedAtMillis) {
        long rtt = Math.max(0L, receivedAtMillis - sentAtMillis);
        lastRttMillis = rtt;
    }

    public static long serverNow() {
        return System.currentTimeMillis() + offsetMillis;
    }

    public static long rttMillis() {
        return lastRttMillis;
    }

    public static void reset() {
        offsetMillis = 0L;
        lastRttMillis = -1L;
    }
}
