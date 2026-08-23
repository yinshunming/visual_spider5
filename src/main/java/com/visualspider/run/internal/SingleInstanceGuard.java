package com.visualspider.run.internal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 单实例保护（M6-2 / docs/specs/m6.md D6 / ADR-0007）。
 *
 * <p>启动早期通过专用 JDBC 连接（非 HikariCP 池）尝试获取 PostgreSQL
 * advisory lock；拿不到锁立即抛 {@link IllegalStateException} 终止 Spring
 * 启动，使两个 JAR 同时连接同一业务库时第二个实例 fail-fast 而非静默并行
 * 调度。
 *
 * <p>关键选择（ADR-0007）：
 * <ul>
 *   <li>{@code pg_try_advisory_lock}（非阻塞）：fail-fast 语义；阻塞等待会让
 *       "等待前例退出"造成静默挂起</li>
 *   <li>专用 {@link DriverManager} 连接（非池）：advisory lock 绑定单一长连接，
 *       池化连接的回收 / 校验会意外释放锁</li>
 *   <li>不写 {@code system_setting} 租约行、不心跳、不 TTL：崩溃后连接断开
 *       锁即释放，PG 自动回收，无需清理任务</li>
 *   <li>实现为 {@link SmartLifecycle}，{@link #getPhase()} 取最小 phase，
 *       早于 dispatcher / scheduler / seed 启动；guard 失败时上述组件根本
 *       不启动</li>
 * </ul>
 */
public final class SingleInstanceGuard implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(SingleInstanceGuard.class);

    /**
     * Advisory lock key。
     *
     * <p>固定常量：取类名 FQN 的 {@link String#hashCode()} 转为 long，保证全
     * JVM 唯一、不与 PG 内部 key 冲突（PG advisory lock key 是 bigint）。修改
     * 类名会让旧实例持有的锁无法被识别为同 key——属接受行为（部署按类名寻
     * 找匹配 key，类名变 = 部署变）。
     */
    public static final long LOCK_KEY =
            (long) "com.visualspider.run.internal.SingleInstanceGuard".hashCode();

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private volatile Connection connection;
    private volatile boolean running;

    public SingleInstanceGuard(String jdbcUrl, String username, String password) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl 不能为空");
        }
        this.jdbcUrl = jdbcUrl;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    @Override
    public void start() {
        try {
            Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
            boolean acquired;
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, LOCK_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    acquired = rs.next() && rs.getBoolean(1);
                }
            }
            if (!acquired) {
                conn.close();
                throw new IllegalStateException(
                        "single instance guard: 另一实例已持有调度锁; 确认只有一个 JAR 连接此库. key="
                                + LOCK_KEY);
            }
            this.connection = conn;
            this.running = true;
            LOG.info("single instance guard: acquired advisory lock key={}", LOCK_KEY);
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "single instance guard: failed to acquire advisory lock: " + ex.getMessage(),
                    ex);
        }
    }

    @Override
    public void stop() {
        Connection conn = this.connection;
        if (conn == null) {
            this.running = false;
            return;
        }
        try {
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, LOCK_KEY);
                ps.execute();
            }
        } catch (SQLException ex) {
            // 连接断开时锁会被 PG 自动回收，记录 WARN 不阻断关闭
            LOG.warn(
                    "single instance guard: pg_advisory_unlock failed (lock auto-released on close): {}",
                    ex.toString());
        } finally {
            try {
                conn.close();
            } catch (SQLException ex) {
                LOG.warn("single instance guard: connection close failed: {}", ex.toString());
            }
            this.connection = null;
            this.running = false;
            LOG.info("single instance guard: released advisory lock key={}", LOCK_KEY);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 最早 phase：早于 dispatcher / scheduler / seed；guard 失败时它们根本不启动
        return Integer.MIN_VALUE + 100;
    }
}
