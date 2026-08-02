package com.visualspider.task.domain;

/**
 * 采集运行等待策略（M3 spec §D6）。
 *
 * <p>字段额外等待时间（{@code extraWaitSeconds}），约束 {@code 0 ≤ extraWaitSeconds ≤ 5}，
 * 默认 0（与 M2 行为一致，无额外等待）。
 *
 * <p>反序列化旧快照时若为 {@code null}，由 {@link TaskDefinition} 紧凑构造器填充默认值
 * {@code WaitPolicy(0)}。
 */
public record WaitPolicy(int extraWaitSeconds) {

    public WaitPolicy {
        if (extraWaitSeconds < 0 || extraWaitSeconds > 5) {
            throw new IllegalArgumentException("extraWaitSeconds 必须在 0-5 之间；got " + extraWaitSeconds);
        }
    }
}