package com.visualspider.run.internal;

import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.run.spi.RunCoordinator;
import com.visualspider.run.spi.RunExecutor;
import com.visualspider.run.spi.RunRecovery;
import com.visualspider.task.spi.TaskCatalog;
import com.visualspider.task.spi.TaskReadiness;
import com.visualspider.task.spi.TaskSnapshotFactory;
import com.visualspider.visualbrowser.BrowserLane;
import com.visualspider.visualbrowser.spi.LanePool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * run 模块 Spring 装配（M3-3 #25 / M3-2 #24 / ADR-0004 / ADR-0006）。
 *
 * <p>装配：
 * <ul>
 *   <li>{@code runLanePool}：3 lane 独立池（与 configLanePool 完全独立）</li>
 *   <li>{@code runRecovery}：启动扫描遗留 run → INTERRUPTED（{@code @Order(HIGHEST+10)}，
 *       早于 dispatcher 启动）</li>
 *   <li>{@code runCoordinator}：start / cancel / get / list / progress</li>
 *   <li>{@code runPageHandleProvider}：lease → per-run RunPageHandle 工厂</li>
 *   <li>{@code runDispatcher}：事件驱动 + 5s 兜底 + CAS 翻 WAITING → RUNNING</li>
 *   <li>{@code singlePageRunExecutor} + {@code listRunExecutor}：两支 {@code RunExecutor}，
 *       dispatcher 按 {@code task.mode} 路由（spec §D15；M4 re-baseline）</li>
 * </ul>
 *
 * <p>队列约定（ADR-0006）：{@code collection_run.status='WAITING'} 即队列；不在 JVM
 * 复制（无 {@code BlockingQueue} / {@code Semaphore} 作为队列副本）。
 */
@Configuration
public class RunModuleConfig {

    @Bean(destroyMethod = "close")
    public RunLanePool runLanePool(
            @Value("${run.lane-pool.capacity:3}") int capacity) {
        int cap = capacity > 0 ? capacity : RunLanePool.DEFAULT_CAPACITY;
        return new RunLanePool(cap, i -> new BrowserLane());
    }

    @Bean
    public RunRecovery runRecovery(RunRepository repository) {
        return new RunRecoveryImpl(repository);
    }

    @Bean
    public RunCoordinator runCoordinator(RunRepository repository,
                                        TaskCatalog taskCatalog,
                                        TaskReadiness taskReadiness,
                                        TaskSnapshotFactory snapshotFactory,
                                        IdentityAccess identityAccess,
                                        RunDispatcher dispatcher) {
        return new RunCoordinatorImpl(repository, taskCatalog, taskReadiness,
                snapshotFactory, identityAccess, dispatcher::scheduleDispatch);
    }

    @Bean
    public RunPageHandleProvider runPageHandleProvider(RunLanePool runLanePool) {
        return new DefaultRunPageHandleProvider(runLanePool);
    }

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("singlePageRunExecutor")
    public RunExecutor singlePageRunExecutor(RunRepository repository,
                                   com.visualspider.result.spi.RunResultSink resultSink,
                                   com.visualspider.extraction.spi.ExtractionPreview extraction,
                                   com.visualspider.visualbrowser.spi.TargetUrlPolicy urlPolicy) {
        return new SinglePageRunExecutor(repository, resultSink, extraction, urlPolicy);
    }

    @Bean
    public com.visualspider.result.internal.UniqueKeyHasher uniqueKeyHasher() {
        return new com.visualspider.result.internal.UniqueKeyHasher();
    }

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("listRunExecutor")
    public RunExecutor listRunExecutor(RunRepository repository,
                                           com.visualspider.result.spi.RunResultSink resultSink,
                                           com.visualspider.extraction.spi.ExtractionPreview extraction,
                                           com.visualspider.visualbrowser.spi.TargetUrlPolicy urlPolicy,
                                           com.visualspider.result.internal.UniqueKeyHasher hasher) {
        return new ListRunExecutor(repository, resultSink, extraction, urlPolicy, hasher);
    }

    @Bean(initMethod = "onContextRefreshed", destroyMethod = "shutdown")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "run.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
    public RunDispatcher runDispatcher(LanePool runLanePool,
                                       RunRepository repository,
                                       @org.springframework.beans.factory.annotation.Qualifier("singlePageRunExecutor")
                                               RunExecutor singlePageExecutor,
                                       @org.springframework.beans.factory.annotation.Qualifier("listRunExecutor")
                                               RunExecutor listExecutor,
                                       RunPageHandleProvider pageHandleProvider) {
        return new RunDispatcher(runLanePool, repository, singlePageExecutor, listExecutor, pageHandleProvider);
    }

    /**
     * 显式声明 {@code runRecovery} 的 {@link ApplicationRunner} 入口；带 {@code @Order}
     * 确保先于 {@code RunDispatcher} 的 {@code onContextRefreshed} 启动（dispatcher
     * 监听 {@code ContextRefreshedEvent}，发生在 {@code ApplicationRunner} 之后）。
     *
     * <p>{@code @ConditionalOnProperty} 默认开启；测试可通过
     * {@code run.recovery.enabled=false} 关闭，避免无 DB 时阻塞 context 加载。
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "run.recovery.enabled", havingValue = "true", matchIfMissing = true)
    public ApplicationRunner runRecoveryRunner(RunRecovery recovery) {
        return args -> recovery.markInterruptedOnStartup();
    }
}