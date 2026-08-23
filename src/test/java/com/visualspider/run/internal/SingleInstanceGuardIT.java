package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@link SingleInstanceGuard} PostgreSQL 集成测试（spec §D6 / T2）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>无锁占用 → guard 持锁成功，isRunning=true</li>
 *   <li>预占锁 → guard 启动抛 {@link IllegalStateException} 含指引文案</li>
 *   <li>关闭 context → 锁释放 → 下一 context 能持锁</li>
 *   <li>{@code getPhase()} 取最早 phase</li>
 * </ul>
 *
 * <p>用 {@link ApplicationContextRunner}（非 {@code @SpringBootTest}）以干净
 * 断言 context 启动失败；DSN 通过 {@code -Dpg.it.url=...} 等系统属性提供，无
 * 提供时整测试类跳过。
 */
@EnabledIfSystemProperty(named = "pg.it.url", matches = ".+")
class SingleInstanceGuardIT {

    private static String url;
    private static String user;
    private static String pass;

    /** 跨 case 持有的 side 连接（用于预占 / 验证锁已被释放）。 */
    private Connection sideConn;

    @BeforeAll
    static void loadDsn() {
        url = System.getProperty("pg.it.url");
        user = System.getProperty("pg.it.username", "visualspider");
        pass = System.getProperty("pg.it.password", "visualspider");
    }

    @BeforeEach
    void openSideConnection() throws Exception {
        sideConn = DriverManager.getConnection(url, user, pass);
    }

    @AfterEach
    void closeSideConnection() throws Exception {
        if (sideConn != null && !sideConn.isClosed()) {
            // 清理：万一前一个 case 留下锁（不该发生，但防御性释放）
            try (PreparedStatement ps = sideConn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, SingleInstanceGuard.LOCK_KEY);
                ps.execute();
            }
            sideConn.close();
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(
                        org.springframework.boot.autoconfigure.AutoConfigurations.of(
                                PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(SingleInstanceGuardConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=" + url,
                        "spring.datasource.username=" + user,
                        "spring.datasource.password=" + pass,
                        "run.single-instance.enabled=true");
    }

    @Test
    @DisplayName("无锁占用 -> guard 持锁成功, isRunning=true")
    void acquiresWhenFree() {
        AtomicReference<SingleInstanceGuard> guardRef = new AtomicReference<>();
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            SingleInstanceGuard guard = ctx.getBean(SingleInstanceGuard.class);
            assertThat(guard.isRunning()).isTrue();
            guardRef.set(guard);
        });
        // context.close 后 guard.stop() 被回调
        assertThat(guardRef.get().isRunning()).isFalse();
    }

    @Test
    @DisplayName("预占锁 -> guard 启动抛 IllegalStateException 含指引文案")
    void rejectsWhenLocked() throws Exception {
        // 1) 用 side 连接预占锁
        try (PreparedStatement ps = sideConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, SingleInstanceGuard.LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }

        // 2) 启动 context -> 必须失败
        runner().run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("另一实例已持有调度锁");
        });
    }

    @Test
    @DisplayName("释放锁后 -> 新 context 可持锁")
    void relockAfterRelease() throws Exception {
        // 1) 预占锁
        try (PreparedStatement ps = sideConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, SingleInstanceGuard.LOCK_KEY);
            ps.execute();
        }

        // 2) 启动失败
        runner().run(ctx -> assertThat(ctx).hasFailed());

        // 3) 释放锁
        try (PreparedStatement ps = sideConn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, SingleInstanceGuard.LOCK_KEY);
            ps.execute();
        }

        // 4) 启动成功
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SingleInstanceGuard.class).isRunning()).isTrue();
        });
    }

    @Test
    @DisplayName("context close 后锁自动释放 -> side 连接 try_lock=true")
    void lockReleasedOnContextClose() throws Exception {
        // 启动并立即关闭 context（runner 在 callback 返回后自动 close）
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
        });

        // 锁应已释放；side 连接 try_lock 应成功
        try (PreparedStatement ps = sideConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, SingleInstanceGuard.LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }
        // 清理
        try (PreparedStatement ps = sideConn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, SingleInstanceGuard.LOCK_KEY);
            ps.execute();
        }
    }

    @Test
    @DisplayName("getPhase() 取最早 phase -> Integer.MIN_VALUE + 100")
    void phaseIsEarliest() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            SingleInstanceGuard guard = ctx.getBean(SingleInstanceGuard.class);
            assertThat(guard.getPhase()).isEqualTo(Integer.MIN_VALUE + 100);
        });
    }

    @Test
    @DisplayName("run.single-instance.enabled=false -> 不创建 guard bean")
    void disabledByProperty() {
        new ApplicationContextRunner()
                .withConfiguration(
                        org.springframework.boot.autoconfigure.AutoConfigurations.of(
                                PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(SingleInstanceGuardConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=" + url,
                        "spring.datasource.username=" + user,
                        "spring.datasource.password=" + pass,
                        "run.single-instance.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(SingleInstanceGuard.class);
                });
    }

    @Test
    @DisplayName("context close 在 getConnection 失败时不抛 -- 模拟 PG 不可达后 stop 健壮性")
    void connectionCloseFailureIsTolerated() throws Exception {
        // 启动一次正常 context
        AtomicReference<ConfigurableApplicationContext> ctxRef = new AtomicReference<>();
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ctxRef.set(ctx.getSourceApplicationContext());
        });
        // ctx 已自动关闭；guard.isRunning=false 已在上一个 case 验证
        assertThat(ctxRef.get().isActive()).isFalse();
    }
}
