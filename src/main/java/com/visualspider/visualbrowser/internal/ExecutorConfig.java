package com.visualspider.visualbrowser.internal;

import com.visualspider.visualbrowser.BrowserLane;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 编辑缓冲防抖调度器装配（M2-4 #20）+ 实匹配校验 lane 装配（M4-3 #33）。
 *
 * <p>固定大小 daemon 调度池，供 {@link EditingBuffer} 5 秒防抖保存；应用关闭时调用
 * {@code shutdown()} 静默停止（不强制 await 正在执行的保存，避免阻塞关闭）。
 * 与 {@code SessionLifecycleTicker} 自有的单线程调度器相互独立。
 *
 * <p>{@code liveReadinessLane} 供 {@link PlaywrightLiveReadinessHook} 每次 check 创建独立
 * 非持久化 Page（spec §D9）。JVM 单例，与 config lane 池相互独立（避免 live check 与配置会话互相阻塞）。
 */
@Configuration
public class ExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService editBufferScheduler(
            @Value("${visualbrowser.edit-buffer.scheduler-pool-size:2}") int poolSize) {
        int size = poolSize > 0 ? poolSize : 2;
        return Executors.newScheduledThreadPool(size, r -> {
            Thread t = new Thread(r, "edit-buffer-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean(destroyMethod = "close")
    public BrowserLane liveReadinessLane() {
        return new BrowserLane();
    }
}
