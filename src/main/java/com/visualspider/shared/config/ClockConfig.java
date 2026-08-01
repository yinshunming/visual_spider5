package com.visualspider.shared.config;

import com.visualspider.shared.time.Clock;
import com.visualspider.shared.time.SystemClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟 Spring 装配（spec §D3）：暴露 {@link SystemClock#INSTANCE} 为生产 {@link Clock} bean。
 *
 * <p>{@code shared.time} 保持框架无关，装配集中在 {@code shared.config}；与 {@code LanePoolConfig}
 * 同属"接口在稳定包、实现在 config 层注册"的模式。
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return SystemClock.INSTANCE;
    }
}
