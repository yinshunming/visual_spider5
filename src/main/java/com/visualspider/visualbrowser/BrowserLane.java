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
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 浏览器 lane：固定平台线程 + 有界命令队列 + 崩溃自愈（M6-4）。
 *
 * <p>Playwright for Java 不是线程安全的，同一 {@link Playwright}/{@link Browser}/{@link BrowserContext}/
 * {@link Page} 必须只在创建它们的线程上调用。本类把所有 Playwright 调用约束到名为
 * {@code browser-lane-{poolName}-{index}} 的单一线程：命令经有界队列投递，队列满时
 * {@code submit} 阻塞调用线程（背压），禁止无界排队。
 * Playwright 对象在 lane 线程上创建/回收，关闭时按 Page -&gt; BrowserContext -&gt; Browser
 * -&gt; Playwright 顺序回收。
 *
 * <p><b>崩溃自愈（spec §D8）</b>：任务执行抛 {@link com.microsoft.playwright.impl.TargetClosedException}
 * 或连接关闭族异常时，{@link LaneState} 转 {@link LaneState#CRASHED}；下次 borrow 由 lane 线程
 * 自身重建 Playwright 资源，恢复 {@link LaneState#HEALTHY}。
 *
 * <p>重建期间该 lane 不回池（池满语义短暂出现），重建失败保持 CRASHED 不无限重试；下一次
 * borrow 触发再次尝试。
 */
public final class BrowserLane implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserLane.class);

    /** 有界命令队列容量：满时 submit 阻塞（背压），禁止无界排队。包级可见供测试断言。 */
    static final int QUEUE_CAPACITY = 64;

    /** 崩溃信号判定（spec §D8）：Playwright 连接 / Target 关闭族。 */
    static boolean isCrashSignal(Throwable t) {
        if (t == null) return false;
        // 解除包装
        while (t.getCause() != null && t != t.getCause()) {
            t = t.getCause();
        }
        String cls = t.getClass().getName();
        if (cls.contains("TargetClosed")) return true;
        if (cls.contains("BrowserClosed")) return true;
        String msg = t.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("playwright connection closed")
                    || lower.contains("browser has been closed")
                    || lower.contains("connection closed")) return true;
        }
        return false;
    }

    private final String poolName;
    private final int poolIndex;
    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    /** 已提交但尚未完成的命令 future，用于 close 时异常完成挂起命令，避免永久挂起。 */
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Thread laneThread;
    private final Consumer<BrowserContext> contextCustomizer;
    private volatile boolean closed = false;
    /** lane 状态：HEALTHY 借出可用 / CRASHED 待重建。 */
    private volatile LaneState state = LaneState.HEALTHY;
    private PlaywrightResources resources;

    /** lane 状态（M6-4）。 */
    public enum LaneState {
        HEALTHY, CRASHED
    }

    /** 持有 Playwright 对象及关闭顺序，在 lane 线程上创建/回收。 */
    private static final class PlaywrightResources implements AutoCloseable {
        final Playwright playwright;
        final Browser browser;
        final BrowserContext context;
        final Page page;

        PlaywrightResources() {
            this(null);
        }

        PlaywrightResources(Consumer<BrowserContext> contextCustomizer) {
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
                if (contextCustomizer != null) {
                    contextCustomizer.accept(c);
                }
                pg = c.newPage();
            } catch (Throwable t) {
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

    /** 测试构造器：不启动 Chromium（initializer 返回 null）。 */
    public BrowserLane() {
        this("test", 0, null, (Supplier<?>) () -> null);
    }

    /** 测试构造器：自定义 initializer。 */
    BrowserLane(Supplier<?> initializer) {
        this("test", 0, null, initializer);
    }

    /** 生产构造器：在 lane 线程上初始化 Chromium，线程名 {@code browser-lane-{poolName}-{index}}。 */
    public BrowserLane(String poolName, int index, Consumer<BrowserContext> contextCustomizer) {
        this(poolName, index, contextCustomizer, PlaywrightResources::new);
    }

    BrowserLane(String poolName, int index, Consumer<BrowserContext> contextCustomizer,
                Supplier<?> initializer) {
        this.poolName = poolName == null ? "anon" : poolName;
        this.poolIndex = index;
        this.contextCustomizer = contextCustomizer;
        this.laneThread = new Thread(this::loop, "browser-lane-" + this.poolName + "-" + index);
        this.laneThread.setDaemon(true);
        this.laneThread.start();
        submit(() -> {
            Object created = initializer.get();
            if (created instanceof PlaywrightResources pr) {
                this.resources = pr;
            }
            return null;
        }).join();
    }

    /**
     * 测试工厂：创建无 Chromium 的 lane（{@code initializer} 返回 null 时不分配 Playwright 资源）。
     */
    public static BrowserLane forTest() {
        return new BrowserLane((Supplier<?>) () -> null);
    }

    private void loop() {
        try {
            while (!closed) {
                Runnable cmd = queue.poll(200, TimeUnit.MILLISECONDS);
                if (cmd != null) {
                    cmd.run();
                    // 任务执行后检查崩溃：CRASHED 状态下重建资源
                    if (state == LaneState.CRASHED) {
                        rebuildOnLaneThread();
                    }
                }
            }
        } catch (InterruptedException e) {
            closed = true;
            Thread.currentThread().interrupt();
        } finally {
            dispose();
        }
    }

    /**
     * 在 lane 线程内重建 Playwright 资源。关闭旧资源（可能已死，逐项吞异常），新建并替换。
     * 重建失败保持 CRASHED，不抛（避免终止 lane 线程）；下次 borrow 触发再次尝试。
     */
    private void rebuildOnLaneThread() {
        LOG.warn("browser lane rebuilding: pool={} index={}", poolName, poolIndex);
        try {
            if (resources != null) {
                resources.close();
                resources = null;
            }
        } catch (Throwable ignored) {
            // 旧资源已死,吞
        }
        try {
            resources = new PlaywrightResources(contextCustomizer);
            state = LaneState.HEALTHY;
            LOG.info("browser lane rebuilt: pool={} index={}", poolName, poolIndex);
        } catch (Throwable t) {
            LOG.error("browser lane rebuild failed: pool={} index={} reason={}",
                    poolName, poolIndex, t.toString());
            // 保持 CRASHED,下次 borrow 再试
        }
    }

    private void dispose() {
        if (resources != null) {
            try {
                resources.close();
            } catch (Throwable ignored) {
            }
            resources = null;
        }
    }

    /**
     * 在 lane 线程上执行 task 并返回结果。
     * 队列满时阻塞调用线程（背压），禁止无界排队；lane 关闭后立即异常完成。
     *
     * <p>若 task 抛崩溃信号（{@link #isCrashSignal}），提交方 future 仍异常完成（沿用既有失败路径），
     * 同时 lane 状态转 CRASHED；下次 borrow 由 lane 线程触发重建。
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
                    T result = task.get();
                    future.complete(result);
                } catch (Throwable t) {
                    if (isCrashSignal(t)) {
                        state = LaneState.CRASHED;
                        LOG.warn("browser lane crashed: pool={} index={} reason={}",
                                poolName, poolIndex, t.toString());
                    }
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

    /** lane 线程名。 */
    public String laneThreadName() {
        return laneThread.getName();
    }

    /** 当前 lane 状态，供 health 聚合。 */
    public LaneState state() {
        return state;
    }

    /** lane 所属池名（{@code config}/{@code run}）。 */
    public String poolName() {
        return poolName;
    }

    /** lane 在池内索引。 */
    public int poolIndex() {
        return poolIndex;
    }

    /** 当前 Page，仅在 lane 线程上访问（通过 submit 的 lambda）。 */
    Page page() {
        return resources == null ? null : resources.page;
    }

    /**
     * 为单次运行在 lane 线程上创建独立、非持久化的 {@link BrowserContext} + Page。
     * 调用方负责在 run 结束按 Page → BrowserContext 顺序释放。
     */
    public Page createRunPage() {
        return createRunPage(this.contextCustomizer);
    }

    public Page createRunPage(Consumer<BrowserContext> customizer) {
        if (resources == null) {
            throw new IllegalStateException("BrowserLane 资源未初始化");
        }
        return submit(() -> {
            BrowserContext ctx = resources.browser.newContext(
                    new Browser.NewContextOptions().setViewportSize(1280, 720));
            if (customizer != null) {
                customizer.accept(ctx);
            }
            return ctx.newPage();
        }).join();
    }

    @Override
    public void close() {
        closed = true;
        laneThread.interrupt();
        Exception closedException = new IllegalStateException("browser lane is closed");
        Runnable drained;
        while ((drained = queue.poll()) != null) {
            // 丢弃
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
            }
        }
    }
}
