package com.visualspider.visualbrowser.internal;

import com.visualspider.visualbrowser.BrowserLane;
import com.visualspider.visualbrowser.spi.LanePool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置 lane 池 Spring 装配（M2-1 #17 / ADR-0004）。
 *
 * <p>生产环境固定 {@value ConfigLanePool#DEFAULT_CAPACITY} 个 {@link BrowserLane}，每个 lane
 * 启动时初始化独立 Playwright + Chromium（headless，1280×720）。容量常量写死在代码中，
 * 配置化延后 M6（ADR-0004）。
 *
 * <p>{@code destroyMethod="close"} 保证应用关闭时按 Page -> BrowserContext -> Browser ->
 * Playwright 顺序回收，避免 Chromium 子进程残留。
 */
@Configuration
public class LanePoolConfig {

    @Bean(destroyMethod = "close")
    public LanePool configLanePool(
            @Value("${visualbrowser.lane-pool.capacity:3}") int capacity) {
        if (capacity <= 0) {
            capacity = ConfigLanePool.DEFAULT_CAPACITY;
        }
        return new ConfigLanePool(capacity, i -> new BrowserLane());
    }
}
