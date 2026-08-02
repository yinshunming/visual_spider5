package com.visualspider.result.spi;

/**
 * 运行事件级别（M3 spec §D5 / {@code run_event.level} CHECK 约束）。
 *
 * <p>与 {@code run_event} 表 {@code level} 列取值一一对应；写入用枚举，存储用 {@code name()}。
 */
public enum RunEventLevel {
    INFO,
    WARN,
    ERROR
}