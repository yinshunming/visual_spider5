package com.visualspider.spike.m0;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * M0 spike 启动类。
 *
 * <p>仅验证 Spring Boot 进程能在 Playwright 依赖位于 classpath 时正常启动；远程浏览器控制由
 * 集成测试 {@code PlaywrightControlIT} 驱动。REST / WebSocket / Vue 留到 M0-2。
 *
 * <p>启动时不会自动打开 Chromium（避免无意义的常驻浏览器进程）；lane 由测试或后续接入按需创建。
 */
@SpringBootApplication
public class SpikeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpikeApplication.class, args);
    }
}
