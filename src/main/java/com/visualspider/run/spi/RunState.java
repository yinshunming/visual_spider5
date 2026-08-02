package com.visualspider.run.spi;

/**
 * 采集运行状态机（M3 spec §D10 / ADR-0006）。
 *
 * <p>7 态：
 * <ul>
 *   <li>{@link #WAITING}：已创建，等待 lane</li>
 *   <li>{@link #RUNNING}：lane 已获取，正在执行</li>
 *   <li>{@link #SUCCESS}：单页提取成功（M3 实际产生）</li>
 *   <li>{@link #PARTIAL_SUCCESS}：部分成功 —— M3 不产生（留位 M4 多页混合成功/失败）</li>
 *   <li>{@link #FAILED}：终态失败</li>
 *   <li>{@link #CANCELLED}：用户取消</li>
 *   <li>{@link #INTERRUPTED}：应用退出，启动恢复标记</li>
 * </ul>
 *
 * <p>迁移图：
 * <pre>
 *   [*] -> WAITING
 *   WAITING -> RUNNING         (派发器获得 lane)
 *   WAITING -> CANCELLED       (排队中取消)
 *   WAITING -> INTERRUPTED     (启动恢复)
 *   RUNNING -> SUCCESS         (单页提取成功)
 *   RUNNING -> FAILED          (入口失败 / 重试耗尽 / 浏览器启动失败)
 *   RUNNING -> CANCELLED       (用户取消)
 *   RUNNING -> INTERRUPTED     (应用退出)
 *   // PARTIAL_SUCCESS: RUNNING -> PARTIAL_SUCCESS —— M3 不迁移（M4 多页可达）
 * </pre>
 */
public enum RunState {
    WAITING,
    RUNNING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED,
    INTERRUPTED
}