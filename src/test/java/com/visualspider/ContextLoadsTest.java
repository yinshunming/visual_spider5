package com.visualspider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 加载完整 ApplicationContext，暴露 Spring 装配缺陷。
 *
 * <p>非 {@code *IT} 后缀，surefire 默认运行；{@code test/application.yml} 关闭 Flyway 且
 * Hikari 延迟连接，不依赖真实 DB 即可装配全部 bean。
 *
 * <p>M8-3：覆盖 {@code visualbrowser.target-url.allow-loopback=false} 以避免
 * {@link com.visualspider.shared.config.LoopbackStartupFailFastValidator} 在无
 * {@code @ActiveProfiles} 的简单 context 加载测试中误触发 fail-fast。其他 IT 已在
 * {@code @ActiveProfiles("it")} 下，loopback 豁免由 profile 白名单旁路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "visualbrowser.target-url.allow-loopback=false")
class ContextLoadsTest {

    @Test
    void contextLoads() {
        // 仅验证上下文加载，断言由 Spring 启动失败隐式提供
    }
}
