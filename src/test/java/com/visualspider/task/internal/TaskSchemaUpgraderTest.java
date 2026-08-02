package com.visualspider.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.Limits;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskStatus;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.spi.TaskRepository;
import java.time.OffsetDateTime;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link TaskSchemaUpgrader} 单元测试（M4 spec §D2）。
 *
 * <p>不依赖真实 PG；用 Mockito 替换 {@link NamedParameterJdbcTemplate} 验证 SQL 参数与影响行数。
 */
@ExtendWith(MockitoExtension.class)
class TaskSchemaUpgraderTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    private TaskRepository repository;

    private TaskSchemaUpgrader upgrader;

    @BeforeEach
    void setUp() {
        upgrader = new TaskSchemaUpgrader(jdbc, repository);
    }

    @Test
    @DisplayName("upgradeV1SinglePage 命中 5 行 → 5")
    void upgradeV1SpReturnsRowsAffected() {
        when(jdbc.update(contains("UPDATE collection_task"), any(MapSqlParameterSource.class)))
                .thenReturn(5);
        int n = upgrader.upgradeV1SinglePage();
        assertThat(n).isEqualTo(5);
    }

    @Test
    @DisplayName("upgradeV1SinglePage 传全局默认 limits")
    void upgradeV1SpUsesGlobalDefaultLimits() {
        when(jdbc.update(contains("UPDATE collection_task"), any(MapSqlParameterSource.class)))
                .thenReturn(1);
        upgrader.upgradeV1SinglePage();
        ArgumentCaptor<MapSqlParameterSource> captor =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("UPDATE collection_task"), captor.capture());
        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("pageLimit")).isEqualTo(Limits.globalDefault().pageLimit());
        assertThat(params.getValue("recordLimit")).isEqualTo(Limits.globalDefault().recordLimit());
        assertThat(params.getValue("durationLimit")).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("downgradeV1ListMissingRule 命中 3 行 → 3")
    void downgradeV1ListMissingRule() {
        when(jdbc.update(eq("""
                UPDATE collection_task
                SET status = ?
                WHERE mode = 'LIST'
                  AND (definition->>'schemaVersion')::int = 1
                  AND NOT (definition ? 'listItemRule')
                """), any(MapSqlParameterSource.class))).thenReturn(3);
        int n = upgrader.downgradeV1ListWithoutRule();
        assertThat(n).isEqualTo(3);
    }

    @Test
    @DisplayName("run：两次 update 都返 0 不抛异常（idle 路径）")
    void runNoopWhenNothingToMigrate() {
        when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(0);
        upgrader.run(new TestApplicationArguments());
        verify(jdbc, times(2)).update(any(String.class), any(MapSqlParameterSource.class));
    }

    @Test
    @DisplayName("run：一次 update 抛 RuntimeException 不阻断启动（异常兜底）")
    void runSwallowsRuntimeException() {
        when(jdbc.update(any(String.class), any(MapSqlParameterSource.class)))
                .thenThrow(new RuntimeException("transient"));
        // 不抛：upgrader 内部 try/catch + LOG.warn
        upgrader.run(new TestApplicationArguments());
        verify(jdbc, times(1)).update(any(String.class), any(MapSqlParameterSource.class));
    }

    @Test
    @DisplayName("isV1ListMissingItemRule 正确识别")
    void isV1ListMissingItemRule() {
        TaskDraft v1ListMissing = newDraft(1, true, null);
        assertThat(TaskSchemaUpgrader.isV1ListMissingItemRule(v1ListMissing)).isTrue();

        TaskDraft v1ListWithRule = newDraft(1, true, new ListItemRule("ul > li"));
        assertThat(TaskSchemaUpgrader.isV1ListMissingItemRule(v1ListWithRule)).isFalse();

        TaskDraft v2Single = newDraft(2, false, null);
        assertThat(TaskSchemaUpgrader.isV1ListMissingItemRule(v2Single)).isFalse();
    }

    @Test
    @DisplayName("defaults() 暴露与 upgrader 同步的 limits（M4 §D10）")
    void defaultsExposed() {
        assertThat(TaskSchemaUpgrader.defaults()).isEqualTo(Limits.globalDefault());
    }

    @Test
    @DisplayName("upgradeIfNeeded V2 draft → 原样返回（idempotent）")
    void upgradeIfNeededV2Idempotent() {
        TaskDraft v2 = newDraft(2, false, null);
        assertThat(upgrader.upgradeIfNeeded(v2)).isSameAs(v2);
    }

    @Test
    @DisplayName("upgradeIfNeeded null → null")
    void upgradeIfNeededNull() {
        assertThat(upgrader.upgradeIfNeeded(null)).isNull();
    }

    private static TaskDraft newDraft(int schemaVersion, boolean isList,
                                       ListItemRule rule) {
        TaskMode m = isList ? new TaskMode.List() : new TaskMode.SinglePage();
        TaskDefinition def = new TaskDefinition(
                schemaVersion, m, "https://example.com", Viewport.DEFAULT,
                null, null, rule, null, Collections.emptyList());
        return new TaskDraft(1L, 2L, "name", m, TaskStatus.READY, schemaVersion, 0L,
                def, OffsetDateTime.now());
    }

    /** 测试用的空 {@link ApplicationArguments} 实现。 */
    private static class TestApplicationArguments implements ApplicationArguments {
        @Override
        public String[] getSourceArgs() {
            return new String[0];
        }

        @Override
        public java.util.Set<String> getOptionNames() {
            return java.util.Set.of();
        }

        @Override
        public boolean containsOption(String name) {
            return false;
        }

        @Override
        public java.util.List<String> getOptionValues(String name) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<String> getNonOptionArgs() {
            return java.util.List.of();
        }
    }
}
