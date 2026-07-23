package com.visualspider.visualbrowser.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/**
 * WebSocket 帧发送有界线程池（M2-1 #17）。
 *
 * <p>替换 M0 spike 的 {@code newCachedThreadPool()}：固定容量、{@link RejectedExecutionException}
 * 让上层回退到 {@link com.visualspider.visualbrowser.spi.FrameSink} 丢弃策略；
 * {@link PreDestroy} 关闭线程池，避免应用关闭时帧发送器残留。
 */
@Component
public final class FrameSenderExecutor {

    private final ThreadFactory factory;
    private final ExecutorService executor;
    private volatile boolean shuttingDown;

    /** 默认 4 个发送线程（每个 WS 连接单线程，混合连接数一般 3）。 */
    public FrameSenderExecutor() {
        this(4);
    }

    public FrameSenderExecutor(int threads) {
        if (threads <= 0) {
            throw new IllegalArgumentException("threads 必须 > 0");
        }
        AtomicLong counter = new AtomicLong();
        this.factory = r -> {
            Thread t = new Thread(r, "ws-frame-sender-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newFixedThreadPool(threads, factory);
    }

    public void submit(Runnable task) {
        if (shuttingDown) {
            throw new RejectedExecutionException("FrameSenderExecutor 已关闭");
        }
        executor.submit(task);
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                // 强制中断后再次等待
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
