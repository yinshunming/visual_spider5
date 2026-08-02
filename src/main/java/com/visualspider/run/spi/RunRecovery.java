package com.visualspider.run.spi;

/**
 * 启动恢复 SPI（M3 spec §D15 / ADR-0006）。
 *
 * <p>启动时把遗留的 WAITING / RUNNING 标记为 INTERRUPTED + stop_reason=APP_INTERRUPTED，
 * 不续跑；已持久化结果仍可查询与导出。
 */
public interface RunRecovery {
    /** 扫描遗留 run，标记 INTERRUPTED；返回被标记的数量。 */
    int markInterruptedOnStartup();
}