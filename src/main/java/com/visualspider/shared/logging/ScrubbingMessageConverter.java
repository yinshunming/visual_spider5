package com.visualspider.shared.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback MessageConverter：在 {@code %msg} 渲染前调用 {@link LoggingScrubber#scrub(String)} 脱敏。
 *
 * <p>由 {@code logback-spring.xml} 的 {@code conversionRule} 注入。
 */
public class ScrubbingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String raw = super.convert(event);
        return LoggingScrubber.scrub(raw);
    }
}
