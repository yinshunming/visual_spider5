package com.visualspider.run.internal;

/**
 * 每用户已有进行中（WAITING+RUNNING）的运行，再启动新 run 即拒（409 + USER_RUN_LIMIT）。
 *
 * <p>M3 spec §D2 / §D19 / ADR-0006：每用户 (W+R)≤1 是产品硬约束，由
 * {@code RunCoordinator.start} 在 in-JVM 锁内同事务做 count-check，
 * 命中即抛本异常。
 */
public final class UserRunLimitException extends RuntimeException {
    public UserRunLimitException(long ownerId) {
        super("用户已有进行中的运行: ownerId=" + ownerId);
    }
}