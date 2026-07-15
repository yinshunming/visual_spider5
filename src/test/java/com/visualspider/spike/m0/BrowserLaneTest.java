package com.visualspider.spike.m0;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BrowserLane} 队列/线程逻辑的纯单元测试，不启动 Chromium（initializer 返回 null）。
 * 覆盖线程亲和性、有界队列背压、关闭后拒绝提交。
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class BrowserLaneTest {

    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void allCommandsRunOnTheSameLaneThread() throws Exception {
        Set<String> observedThreads = ConcurrentHashMap.newKeySet();
        try (BrowserLane lane = new BrowserLane(() -> null)) {
            String laneName = lane.laneThreadName();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                futures.add(lane.submit(() -> {
                    observedThreads.add(Thread.currentThread().getName());
                    return null;
                }));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(observedThreads).containsExactly(laneName);
        }
    }

    @Test
    void submitBlocksWhenQueueIsFullThenCompletesAfterDrain() throws Exception {
        CountDownLatch holdLane = new CountDownLatch(1);
        CountDownLatch taskStarted = new CountDownLatch(1);
        try (BrowserLane lane = new BrowserLane(() -> null)) {
            // 慢任务占住 lane 线程（异步，不 join），用 taskStarted 确认它已在 lane 线程执行
            lane.submit(() -> {
                taskStarted.countDown();
                try {
                    holdLane.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            assertThat(taskStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // 填满队列：lane 被占住不消费，命令堆积在队列里
            for (int i = 0; i < BrowserLane.QUEUE_CAPACITY; i++) {
                lane.submit(() -> null);
            }

            // 第 QUEUE_CAPACITY+1 个命令应因队列满而阻塞
            CompletableFuture<Void> blocked = CompletableFuture.runAsync(
                    () -> lane.submit(() -> null).join());
            Thread.sleep(300);
            assertThat(blocked.isDone()).as("队列满后 submit 应阻塞").isFalse();

            holdLane.countDown();
            blocked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(blocked.isDone()).isTrue();
        }
    }

    @Test
    void submitFailsAfterClose() {
        BrowserLane lane = new BrowserLane(() -> null);
        lane.close();
        CompletableFuture<Object> future = lane.submit(() -> null);
        assertThat(future).isCompletedExceptionally();
    }

    @Test
    void pendingCommandsFailOnClose() throws Exception {
        CountDownLatch holdLane = new CountDownLatch(1);
        CountDownLatch taskStarted = new CountDownLatch(1);
        BrowserLane lane = new BrowserLane(() -> null);
        lane.submit(() -> {
            taskStarted.countDown();
            try {
                holdLane.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        assertThat(taskStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 挂起命令：lane 被占住，此命令入队但未执行
        CompletableFuture<Object> pendingCmd = lane.submit(() -> "never");
        assertThat(pendingCmd.isDone()).isFalse();

        lane.close();
        // close 必须异常完成挂起命令，而非让其永久挂起
        assertThat(pendingCmd).isCompletedExceptionally();
    }
}
