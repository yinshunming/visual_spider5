package com.visualspider.shared.logging;

import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link LoggingScrubber} 暴露给业务代码 / 测试用。
 *
 * <p>实际接入 logback 通过 {@code src/main/resources/logback-spring.xml} 完成；
 * 此处不内嵌 logback API 避免 Spring Boot 3.4 logback 1.5 的 API 差异。
 *
 * <p>日志脱敏的入口统一是 {@link LoggingScrubber#scrub(String)}；
 * {@code LogScrubbingTest} 直接调用验证。
 */
@Configuration
public class LoggingPatternConfigurer {

    /**
     * 测试 / runtime 复用入口；与 {@link LoggingScrubber#scrub(String)} 等价。
     */
    public static String scrubMessage(String message) {
        return LoggingScrubber.scrub(message);
    }
}
