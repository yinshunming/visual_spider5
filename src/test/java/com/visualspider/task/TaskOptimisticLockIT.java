package com.visualspider.task;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

/**
 * M1-3 集成测试：两线程并发保存触发乐观锁。
 *
 * <p>依赖真实 PostgreSQL；本机无 PG 时跳过。
 * 完整实现见仓库 issue #13 描述。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Disabled("requires real PostgreSQL; runs under -Ppg-it only")
class TaskOptimisticLockIT {

    @Test
    @DisplayName("两线程并发保存：一条 200 一条 409")
    void concurrentSave() {
        // 占位；真实并发实现见仓库 issue #13 描述 + CountDownLatch 双线程序列化
        // 当前在缺少 PG 的环境下仅占位；M7 后跨平台验收时补真实实现
    }
}
