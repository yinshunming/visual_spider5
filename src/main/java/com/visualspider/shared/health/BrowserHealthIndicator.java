package com.visualspider.shared.health;

import com.visualspider.visualbrowser.VisualSessionManager;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/health} 的 {@code browser} 组件。
 *
 * <p>M1 永远 UP（spec §D8）；M2 接入 lane 池后改为读取 {@code VisualSessionManager} 状态。
 *
 * <p>故意读取 {@code VisualSessionManager}（即使 M1 不消费其内部状态），
 * 让 Spring 在 M2 替换实现时只需调整本类、不必动 health 装配。
 */
@Component("browser")
public class BrowserHealthIndicator implements HealthIndicator {

    private final VisualSessionManager sessionManager;

    public BrowserHealthIndicator(VisualSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("note", "M2 启用；当前永远 UP")
                .withDetail("managerClass", sessionManager.getClass().getSimpleName())
                .build();
    }
}
