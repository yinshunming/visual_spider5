package com.visualspider.visualbrowser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 浏览器 lane：拥有一个固定平台线程与有界命令队列。
 *
 * <p>Playwright for Java 不是线程安全的，同一 {@link Playwright}/{@link Browser}/{@link BrowserContext}/
 * {@link Page} 必须只在创建它们的线程上调用。本类把所有 Playwright 调用约束到名为 {@code browser-lane-1}
 * 的单一线程：命令经有界队列投递，队列满时 {@code submit} 阻塞调用线程（背压），禁止无界排队。
 * Playwright 对象在 lane 线程上创建，关闭时按 Page -&gt; BrowserContext -&gt; Browser -&gt; Playwright 顺序回收。
 *
 * <p>M0 spike 雏形；M2 会演进为 visualbrowser 模块的 adapter。
 */
public final class BrowserLane implements AutoCloseable {

    /** 有界命令队列容量：满时 submit 阻塞（背压），禁止无界排队。包级可见供测试断言。 */
    static final int QUEUE_CAPACITY = 64;

    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    /** 已提交但尚未完成的命令 future，用于 close 时异常完成挂起命令，避免永久挂起。 */
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Thread laneThread;
    private volatile boolean closed = false;
    private PlaywrightResources resources;

    /** 持有 Playwright 对象及关闭顺序，在 lane 线程上创建/回收。 */
    private static final class PlaywrightResources implements AutoCloseable {
        final Playwright playwright;
        final Browser browser;
        final BrowserContext context;
        final Page page;

        PlaywrightResources() {
            Playwright p = null;
            Browser b = null;
            BrowserContext c = null;
            Page pg = null;
            try {
                p = Playwright.create();
                b = p.chromium().launch(
                        new BrowserType.LaunchOptions().setHeadless(true));
                c = b.newContext(
                        new Browser.NewContextOptions().setViewportSize(1280, 720));
                pg = c.newPage();
            } catch (Throwable t) {
                // 部分初始化失败时按序回收已创建对象，避免遗留 driver/browser 子进程
                closeQuietly(pg);
                closeQuietly(c);
                closeQuietly(b);
                closeQuietly(p);
                throw t;
            }
            this.playwright = p;
            this.browser = b;
            this.context = c;
            this.page = pg;
        }

        @Override
        public void close() {
            closeQuietly(page);
            closeQuietly(context);
            closeQuietly(browser);
            closeQuietly(playwright);
        }
    }

    /** 生产构造器：在 lane 线程上初始化 Chromium（headless，固定视口 1280×720）。 */
    public BrowserLane() {
        this(PlaywrightResources::new);
    }

    /** 测试构造器：自定义 lane 线程启动逻辑，用于不依赖 Chromium 的队列/线程测试。 */
    BrowserLane(Supplier<?> initializer) {
        laneThread = new Thread(this::loop, "browser-lane-1");
        laneThread.setDaemon(true);
        laneThread.start();
        submit(() -> {
            Object created = initializer.get();
            if (created instanceof PlaywrightResources pr) {
                this.resources = pr;
            }
            return null;
        }).join();
    }

    private void loop() {
        try {
            while (!closed) {
                Runnable cmd = queue.poll(200, TimeUnit.MILLISECONDS);
                if (cmd != null) {
                    cmd.run();
                }
            }
        } catch (InterruptedException e) {
            // 外部中断也视作关闭，确保后续 submit 快速失败而非永久挂起
            closed = true;
            Thread.currentThread().interrupt();
        } finally {
            dispose();
        }
    }


    /**
     * 测试用工厂：创建无 Chromium 的 lane（{@code initializer} 返回 null 时不分配 Playwright 资源）。
     * 用于 {@code ConfigLanePoolTest} 等不依赖真实浏览器的测试。
     */
    public static BrowserLane forTest() {
        return new BrowserLane(() -> null);
    }

    private void dispose() {
        if (resources != null) {
            resources.close();
            resources = null;
        }
    }

    /**
     * 在 lane 线程上执行 task 并返回结果。
     * 队列满时阻塞调用线程（背压），禁止无界排队；lane 关闭后立即异常完成。
     */
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("browser lane is closed"));
            return future;
        }
        pending.add(future);
        future.whenComplete((r, t) -> pending.remove(future));
        try {
            queue.put(() -> {
                try {
                    future.complete(task.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(future);
            future.completeExceptionally(e);
        }
        return future;
    }

    /** lane 线程名，供测试断言线程亲和性。 */
    public String laneThreadName() {
        return laneThread.getName();
    }

    /** 当前 Page，仅在 lane 线程上访问（通过 submit 的 lambda）。 */
    Page page() {
        return resources == null ? null : resources.page;
    }

    @Override
    public void close() {
        closed = true;
        laneThread.interrupt();
        Exception closedException = new IllegalStateException("browser lane is closed");
        // 清空队列，释放可能阻塞在 queue.put 的调用线程；丢弃的命令其 future 由 pending 失败处理
        Runnable drained;
        while ((drained = queue.poll()) != null) {
            // 丢弃：对应 future 在 pending 中，下面统一异常完成
        }
        for (CompletableFuture<?> f : pending) {
            f.completeExceptionally(closedException);
        }
        try {
            laneThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 回收阶段忽略单个对象的关闭异常
            }
        }
    }
}
