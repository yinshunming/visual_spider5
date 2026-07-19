package com.visualspider.task.spi;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.task.domain.TaskSnapshot;

/**
 * 任务运行时快照 SPI。M1-3 占位实现抛 {@code UnsupportedOperationException("M3 启用")}；
 * M3 才接真实实现（不可变 snapshot + version 校验）。
 */
public interface TaskSnapshotFactory {
    TaskSnapshot snapshot(long taskId, ActorId actor);
}
