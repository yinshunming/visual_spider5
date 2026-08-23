package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * {@link SingleInstanceGuard} 单元测试（spec §D6 / T1）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code pg_try_advisory_lock=true} → 启动成功、isRunning=true</li>
 *   <li>{@code pg_try_advisory_lock=false} → 抛 {@link IllegalStateException} 含指引文案，
 *       connection 已关闭</li>
 *   <li>SQLException → 抛 {@link IllegalStateException} 含 cause</li>
 *   <li>{@code stop()} → 执行 {@code pg_advisory_unlock} + close connection</li>
 *   <li>空 jdbcUrl 构造即抛异常</li>
 *   <li>{@code getPhase()} 取最早 phase</li>
 * </ul>
 *
 * <p>用 {@link Mockito#mockStatic(Class)} 替换 {@link DriverManager#getConnection}，
 * 让测试无需真实 PG 即可断言 SQL 行为。
 */
class SingleInstanceGuardTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/test";
    private static final String USER = "tester";
    private static final String PASS = "secret";

    /**
     * SQLException 构造在 JDK 21 会调用 {@link DriverManager#getLogWriter()},
     * 与 mockStatic 冲突 → 提前到类加载期构造,绕开 mock 期间构造。
     */
    private static final SQLException REFUSED = new SQLException("connection refused");
    private static final SQLException UNLOCK_FAILED = new SQLException("unlock failed");
    private static final SQLException CLOSE_FAILED = new SQLException("close failed");

    private MockedStatic<DriverManager> driverManagerMock;
    private Connection connection;
    private PreparedStatement tryLockStmt;
    private PreparedStatement unlockStmt;
    private ResultSet tryLockRs;

    @BeforeEach
    void setUp() throws SQLException {
        driverManagerMock = mockStatic(DriverManager.class);
        connection = mock(Connection.class);
        tryLockStmt = mock(PreparedStatement.class);
        unlockStmt = mock(PreparedStatement.class);
        tryLockRs = mock(ResultSet.class);

        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(tryLockStmt);
        when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlockStmt);
        when(tryLockStmt.executeQuery()).thenReturn(tryLockRs);
        driverManagerMock.when(() -> DriverManager.getConnection(URL, USER, PASS))
                .thenReturn(connection);
    }

    @AfterEach
    void tearDown() {
        driverManagerMock.close();
    }

    @Test
    @DisplayName("try_lock=true -> 启动成功, isRunning=true, 连接未关闭")
    void tryLockSuccessAcquires() throws SQLException {
        when(tryLockRs.next()).thenReturn(true);
        when(tryLockRs.getBoolean(1)).thenReturn(true);

        SingleInstanceGuard guard = new SingleInstanceGuard(URL, USER, PASS);
        guard.start();

        assertThat(guard.isRunning()).isTrue();
        // start 期间不应关闭 connection（锁还在持）
        verify(connection, never()).close();
    }

    @Test
    @DisplayName("try_lock=false -> 抛 IllegalStateException 含指引文案, 连接已关闭")
    void tryLockFailureThrowsAndCloses() throws SQLException {
        when(tryLockRs.next()).thenReturn(true);
        when(tryLockRs.getBoolean(1)).thenReturn(false);

        SingleInstanceGuard guard = new SingleInstanceGuard(URL, USER, PASS);

        assertThatThrownBy(guard::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("另一实例已持有调度锁");

        assertThat(guard.isRunning()).isFalse();
        // 失败路径必须关闭已开的 connection（不泄露）
        verify(connection, times(1)).close();
    }

    @Test
    @DisplayName("pg_try_advisory_lock 返回空结果集 -> 抛 IllegalStateException")
    void tryLockEmptyResultSet() throws SQLException {
        when(tryLockRs.next()).thenReturn(false);

        SingleInstanceGuard guard = new SingleInstanceGuard(URL, USER, PASS);

        assertThatThrownBy(guard::start)
                .isInstanceOf(IllegalStateException.class);
        verify(connection, times(1)).close();
    }

    @Test
    @DisplayName("DriverManager.getConnection 抛 SQLException -> IllegalStateException 含 cause")
    void getConnectionFailure() {
        // 重置 setUp 的 thenReturn,改为抛异常;reset 才能重新 stub 同一静态方法
        driverManagerMock.reset();
        driverManagerMock.when(() -> DriverManager.getConnection(URL, USER, PASS))
                .thenThrow(REFUSED);

        SingleInstanceGuard guard = new SingleInstanceGuard(URL, USER, PASS);

        assertThatThrownBy(guard::start)
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(SQLException.class)
                .hasMessageContaining("connection refused");
        assertThat(guard.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stop() 执行 pg_advisory_unlock + close, isRunning=false")
    void stopReleasesLock() throws SQLException {
        when(tryLockRs.next()).thenReturn(true);
        when(tryLockRs.getBoolean(1)).thenReturn(true);
        SingleInstanceGuard guard = new SingleInstanceGuard(URL, USER, PASS);
        guard.start();

        guard.stop();

        verify(unlockStmt, times(1)).setLong(eq(1), eq(SingleInstanceGuard.LOCK_KEY));
        verify(unlockStmt, times(1)).execute();
        verify(connection, times(1)).close();
        assertThat(guard.isRunning()).isFalse();
    }

    @Test
    @DisplayName("未启动就 stop() -> 不抛异常, isRunning=false")
    void stopWithoutStartIsNoop() throws SQLException {
        SingleInstanceGuard guard = new SingleInstanceGuard(URL, USER, PASS);

        guard.stop();

        verify(connection, never()).close();
        verify(unlockStmt, never()).execute();
        assertThat(guard.isRunning()).isFalse();
    }

    @Test
    @DisplayName("unlock 抛 SQLException -> stop 仍关闭连接, isRunning=false")
    void unlockFailureDoesNotBlockClose() throws SQLException {
        when(tryLockRs.next()).thenReturn(true);
        when(tryLockRs.getBoolean(1)).thenReturn(true);
        doThrow(UNLOCK_FAILED).when(unlockStmt).execute();
        SingleInstanceGuard guard = new SingleInstanceGuard(URL, USER, PASS);
        guard.start();

        guard.stop();

        verify(connection, times(1)).close();
        assertThat(guard.isRunning()).isFalse();
    }

    @Test
    @DisplayName("close 抛 SQLException -> stop 不再抛, 状态正确")
    void closeFailureIsSwallowed() throws SQLException {
        when(tryLockRs.next()).thenReturn(true);
        when(tryLockRs.getBoolean(1)).thenReturn(true);
        doThrow(CLOSE_FAILED).when(connection).close();
        SingleInstanceGuard guard = new SingleInstanceGuard(URL, USER, PASS);
        guard.start();

        guard.stop();

        assertThat(guard.isRunning()).isFalse();
    }

    @Test
    @DisplayName("jdbcUrl 为空 -> 构造即抛 IllegalArgumentException")
    void emptyJdbcUrlRejected() {
        assertThatThrownBy(() -> new SingleInstanceGuard("", USER, PASS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SingleInstanceGuard(null, USER, PASS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getPhase() 取最早 phase -> Integer.MIN_VALUE + 100")
    void earliestPhase() {
        SingleInstanceGuard guard = new SingleInstanceGuard(URL, USER, PASS);
        assertThat(guard.getPhase()).isEqualTo(Integer.MIN_VALUE + 100);
    }

    @Test
    @DisplayName("DriverManager 未设置时, getConnection 仍走 mock -- 验证 user/pass 透传")
    void credentialsArePassedThrough() throws SQLException {
        when(tryLockRs.next()).thenReturn(true);
        when(tryLockRs.getBoolean(1)).thenReturn(true);

        SingleInstanceGuard guard = new SingleInstanceGuard(URL, USER, PASS);
        guard.start();

        driverManagerMock.verify(() -> DriverManager.getConnection(URL, USER, PASS), times(1));
        // 锁 key 也必须透传给 PG
        verify(tryLockStmt, times(1)).setLong(eq(1), eq(SingleInstanceGuard.LOCK_KEY));
        verify(tryLockStmt, times(1)).executeQuery();
        // 仅 prepareStatement 过一次 try_lock SQL（未 unlock,因为 start 还未 stop）
        verify(connection, times(1)).prepareStatement("SELECT pg_try_advisory_lock(?)");
    }
}
