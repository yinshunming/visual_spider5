package com.visualspider.task.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.task.domain.TaskSnapshot;
import com.visualspider.task.spi.TaskSnapshotFactory;
import org.springframework.stereotype.Service;

/**
 * {@link TaskSnapshotFactory} M1-3 占位实现：抛 {@link UnsupportedOperationException("M3 启用")}。
 *
 * <p>M3 才接真实实现（不可变 snapshot + version 校验）。
 */
@Service
public class UnsupportedTaskSnapshotFactory implements TaskSnapshotFactory {
    @Override
    public TaskSnapshot snapshot(long taskId, ActorId actor) {
        throw new UnsupportedOperationException("TaskSnapshotFactory M3 启用");
    }
}
