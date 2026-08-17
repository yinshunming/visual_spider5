package com.visualspider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动类。
 *
 * <p>{@code @SpringBootApplication} 默认扫描 {@code com.visualspider.*}，
 * 覆盖 {@code identity} / {@code task} / {@code visualbrowser} /
 * {@code extraction} / {@code run} / {@code result} / {@code shared} 各业务模块。
 *
 * <p>启动时不会自动打开 Chromium（避免无意义的常驻浏览器进程）；
 * lane 由配置会话接入或后续 visualbrowser 模块按需创建。
 *
 * <p>由 M0 spike 阶段启动类升入顶层并改名：类名去掉 spike 痕迹，包前缀去 spike 段；
 * 其余 M0 spike 18 个生产类在 M0.5-T2 迁入 {@code com.visualspider.visualbrowser}。
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}