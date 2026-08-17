package com.visualspider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 加载完整 ApplicationContext，暴露 Spring 装配缺陷。
 *
 * <p>非 {@code *IT} 后缀，surefire 默认运行；{@code test/application.yml} 关闭 Flyway 且
 * Hikari 延迟连接，不依赖真实 DB 即可装配全部 bean。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContextLoadsTest {

    @Test
    void contextLoads() {
        // 仅验证上下文加载，断言由 Spring 启动失败隐式提供
    }
}
