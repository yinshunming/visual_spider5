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
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskStatus;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.spi.TaskRepository;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
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
 * {@link TaskSchemaUpgrader} 单元测试（M4 §D2 / M5 §D3）。
 *
 * <p>不依赖真实 PG；用 Mockito 替换 {@link NamedParameterJdbcTemplate} 验证 SQL 参数与影响行数。
 * 真实 PG V2 -> V3 升级路径留 IT 阶段跟进（与 M4 V1 -> V2 同症，诚实标注）。
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
                SET status = :status
                WHERE mode = 'LIST'
                  AND (definition->>'schemaVersion')::int = 1
                  AND NOT (definition ? 'listItemRule')
                """), any(MapSqlParameterSource.class))).thenReturn(3);
        int n = upgrader.downgradeV1ListWithoutRule();
        assertThat(n).isEqualTo(3);
    }

    @Test
    @DisplayName("upgradeV2ToV3 命中 4 行 → 4（SQL 用 :status 同款命名参数）")
    void upgradeV2ToV3ReturnsRowsAffected() {
        when(jdbc.update(contains("UPDATE collection_task"), any(MapSqlParameterSource.class)))
                .thenReturn(4);
        int n = upgrader.upgradeV2ToV3();
        assertThat(n).isEqualTo(4);
    }

    @Test
    @DisplayName("upgradeV2ToV3 SQL 用 jsonb_set 把 schemaVersion 改 3")
    void upgradeV2ToV3UsesJsonbSet() {
        when(jdbc.update(contains("UPDATE collection_task"), any(MapSqlParameterSource.class)))
                .thenReturn(1);
        upgrader.upgradeV2ToV3();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("jsonb_set(definition, '{schemaVersion}', '3'::jsonb)");
        assertThat(sql).contains("schema_version = 3");
        assertThat(sql).contains("(definition->>'schemaVersion')::int = 2");
    }

    @Test
    @DisplayName("run：三次 update 都返 0 不抛异常（idle 路径）")
    void runNoopWhenNothingToMigrate() {
        when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(0);
        upgrader.run(new TestApplicationArguments());
        // V1->V2 + V1 LIST downgrade + V2->V3 共 3 次 update
        verify(jdbc, times(3)).update(any(String.class), any(MapSqlParameterSource.class));
    }

    @Test
    @DisplayName("run：单次 update 抛 RuntimeException 不阻断启动（异常兜底）")
    void runSwallowsRuntimeException() {
        when(jdbc.update(any(String.class), any(MapSqlParameterSource.class)))
                .thenThrow(new RuntimeException("transient"));
        // V1->V2 抛异常后被 catch, V1 LIST downgrade 与 V2->V3 仍尝试
        upgrader.run(new TestApplicationArguments());
        verify(jdbc, times(3)).update(any(String.class), any(MapSqlParameterSource.class));
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
    @DisplayName("upgradeIfNeeded V3 draft → 原样返回（idempotent）")
    void upgradeIfNeededV3Idempotent() {
        TaskDraft v3 = newDraft(3, false, null);
        assertThat(upgrader.upgradeIfNeeded(v3)).isSameAs(v3);
    }

    @Test
    @DisplayName("upgradeIfNeeded V2 draft → 内存升 V3，paginationRule=null 默认")
    void upgradeIfNeededV2UpgradesInMemory() {
        TaskDraft v2 = newDraft(2, false, null);
        TaskDraft upgraded = upgrader.upgradeIfNeeded(v2);
        assertThat(upgraded).isNotSameAs(v2);
        assertThat(upgraded.schemaVersion()).isEqualTo(3);
        assertThat(upgraded.definition().schemaVersion()).isEqualTo(3);
        assertThat(upgraded.definition().paginationRule()).isNull();
    }

    @Test
    @DisplayName("upgradeIfNeeded V1 draft → 内存升 V3，紧凑构造器填 limits 默认")
    void upgradeIfNeededV1UpgradesInMemory() {
        TaskDraft v1 = newDraft(1, false, null);
        TaskDraft upgraded = upgrader.upgradeIfNeeded(v1);
        assertThat(upgraded.schemaVersion()).isEqualTo(3);
        assertThat(upgraded.definition().schemaVersion()).isEqualTo(3);
        assertThat(upgraded.definition().limits()).isEqualTo(Limits.globalDefault());
    }

    @Test
    @DisplayName("upgradeIfNeeded(TaskDefinition) V2 → 内存升 V3（writer 路径）")
    void upgradeIfNeededDefinitionV2() {
        TaskDefinition v2 = new TaskDefinition(
                2, new TaskMode.SinglePage(), "https://example.com", Viewport.DEFAULT,
                null, null, null, null,
                List.of(new com.visualspider.task.domain.FieldDefinition("t",
                        com.visualspider.task.domain.FieldSource.VISIBLE_TEXT, "h1",
                        null, SelectorType.CSS, com.visualspider.task.domain.ResultType.TEXT,
                        com.visualspider.task.domain.TrimPolicy.TRIM, null, true)));
        TaskDefinition upgraded = upgrader.upgradeIfNeeded(v2);
        assertThat(upgraded.schemaVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("upgradeIfNeeded(TaskDefinition) V3 → 原样返回（idempotent）")
    void upgradeIfNeededDefinitionV3Idempotent() {
        TaskDefinition v3 = new TaskDefinition(
                3, new TaskMode.SinglePage(), "https://example.com", Viewport.DEFAULT,
                null, null, null, null, null, Collections.emptyList());
        assertThat(upgrader.upgradeIfNeeded(v3)).isSameAs(v3);
    }

    @Test
    @DisplayName("upgradeIfNeeded null TaskDraft → null")
    void upgradeIfNeededNull() {
        assertThat(upgrader.upgradeIfNeeded((TaskDraft) null)).isNull();
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