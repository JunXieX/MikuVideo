package dev.junxiex.mikuvideo.client.media;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * 解码引擎内部线程工具。
 *
 * <p>所有线程都是守护线程，并以 {@code mikuvideo-} 前缀加序号命名，
 * 便于在线程转储里区分"是不是解码引擎泄漏的线程"。</p>
 *
 * @author JunXieX
 */
final class MediaThreads {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private MediaThreads() {
    }

    /** 创建 {@code mikuvideo-} 前缀的守护线程（尚未启动）。 */
    static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, "mikuvideo-" + name + "-" + SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    /** 供单线程执行器使用的线程工厂。 */
    static ThreadFactory factory(String name) {
        return runnable -> daemon(name, runnable);
    }

    /** 当前线程是否由本引擎创建；用于避免引擎线程互相等待造成死锁。 */
    static boolean isWorkerThread() {
        String name = Thread.currentThread().getName();
        return name != null && name.startsWith("mikuvideo-");
    }

    /** 可中断休眠；返回 false 表示线程被中断。 */
    static boolean park(long nanos) {
        if (nanos <= 0L) {
            return true;
        }
        LockSupport.parkNanos(nanos);
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /** 带超时 join，永不阻塞调用方超过 {@code millis}。 */
    static void join(Thread thread, long millis) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(Math.max(1L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
