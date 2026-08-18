package com.visualspider.task.internal;

import com.visualspider.task.domain.Limits;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskStatus;
import com.visualspider.task.spi.TaskRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * schemaVersion 启动 hook（M4 spec §D2 / M5 spec §D3）。
 *
 * <p>在 {@link com.visualspider.run.internal.RunRecoveryImpl} 之后、
 * {@code RunDispatcher.onContextRefreshed} 之前执行（{@code @Order(HIGHEST + 20)}）。
 * 顺序约定见 {@code docs/specs/m4.md} §D2 / §T1、{@code docs/specs/m5.md} §D3。
 *
 * <p>行为（同一 upgrader 内顺序处理；各分支独立 try/catch，前序分支失败不阻断后续）：
 * <ol>
 *   <li>V1 SINGLE_PAGE -> V2：补 {@code limits} + 提升 schemaVersion=2</li>
 *   <li>V1 LIST 缺 {@code listItemRule} -> 一律降 DRAFT（V1 时期 LIST 任务已无规则，
 *       安全降级以避免运行时被错误地认为可执行）；LOG WARN</li>
 *   <li>V2 -> V3（M5）：仅 bump {@code schemaVersion}；{@code paginationRule} 不显式
 *       {@code jsonb_set} null（缺省即 null，等价"只跑当前页"，与 V2 LIST 任务行为一致）。
 *       V1 任务在同一启动内先走 V1->V2 分支再被本分支拾起</li>
 *   <li>V3 -> no-op（idempotent）</li>
 * </ol>
 *
 * <p>读取路径双层防护：{@code TaskCatalogImpl.read} / {@code saveDraft} 也调用
 * {@link #upgradeIfNeeded(TaskDraft)} / {@link #upgradeIfNeeded(TaskDefinition)} 兜底。
 *
 * <p><b>已知未覆盖（诚实标注）</b>：本 upgrader 的真实 PG 升级路径（V1->V2、V2->V3）
 * 仅单元测试覆盖（mock JdbcTemplate）；真实 PG 升级路径留 IT 阶段跟进。
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TaskSchemaUpgrader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(TaskSchemaUpgrader.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TaskRepository repository;

    public TaskSchemaUpgrader(NamedParameterJdbcTemplate jdbc, TaskRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 各分支独立兜底：前序分支失败只影响自身，不阻断后续分支（spec §D3 顺序处理）。
        try {
            int upgraded = upgradeV1SinglePage();
            if (upgraded > 0) {
                LOG.info("M4 schema upgrader: upgraded {} SINGLE_PAGE -> V2", upgraded);
            }
        } catch (RuntimeException ex) {
            LOG.warn("M4 schema upgrader V1->V2 failed: {}", ex.getMessage(), ex);
        }
        try {
            int downgraded = downgradeV1ListWithoutRule();
            if (downgraded > 0) {
                LOG.info("M4 schema upgrader: downgraded {} LIST -> DRAFT", downgraded);
            }
        } catch (RuntimeException ex) {
            LOG.warn("M4 schema upgrader V1 LIST downgrade failed: {}", ex.getMessage(), ex);
        }
        try {
            int bumped = upgradeV2ToV3();
            if (bumped > 0) {
                LOG.info("M5 schema upgrader: bumped {} tasks V2 -> V3", bumped);
            } else {
                LOG.info("M5 schema upgrader: no V2 tasks to migrate");
            }
        } catch (RuntimeException ex) {
            LOG.warn("M5 schema upgrader V2->V3 failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * V1 SINGLE_PAGE -> V2：用 {@link Limits#globalDefault()} 补 limits 字段并 bump。
     *
     * <p>同时把 JSONB 内嵌的 {@code schemaVersion} 升到 2，否则后续
     * {@link #upgradeV2ToV3()} 的 {@code WHERE (definition->>'schemaVersion')::int = 2}
     * 不会命中这些任务,JSONB/列值长期不一致。
     */
    int upgradeV1SinglePage() {
        Limits defaults = Limits.globalDefault();
        String sql = """
                UPDATE collection_task
                SET schema_version = 2,
                    definition = jsonb_set(
                        jsonb_set(definition, '{schemaVersion}', '2'::jsonb),
                        '{limits}',
                        jsonb_build_object('pageLimit', :pageLimit,
                                           'recordLimit', :recordLimit,
                                           'durationLimit', :durationLimit))
                WHERE (definition->>'schemaVersion')::int = 1
                  AND mode = 'SINGLE_PAGE'
                """;
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("pageLimit", defaults.pageLimit())
                .addValue("recordLimit", defaults.recordLimit())
                .addValue("durationLimit", formatDuration(defaults.durationLimit())));
    }

    /**
     * V1 LIST 任务缺 {@code listItemRule} -> 状态降为 DRAFT。
     */
    int downgradeV1ListWithoutRule() {
        String sql = """
                UPDATE collection_task
                SET status = :status
                WHERE mode = 'LIST'
                  AND (definition->>'schemaVersion')::int = 1
                  AND NOT (definition ? 'listItemRule')
                """;
        return jdbc.update(sql, new MapSqlParameterSource().addValue("status", TaskStatus.DRAFT.name()));
    }

    /**
     * V2 -> V3（M5 spec §D3）：仅 bump {@code schemaVersion}（JSONB 内嵌值 + 列值同步），
     * 不引入数据变更；{@code paginationRule} 缺省即 null（等价"只跑当前页"）。
     */
    int upgradeV2ToV3() {
        String sql = """
                UPDATE collection_task
                SET schema_version = 3,
                    definition = jsonb_set(definition, '{schemaVersion}', '3'::jsonb)
                WHERE (definition->>'schemaVersion')::int = 2
                """;
        return jdbc.update(sql, new MapSqlParameterSource());
    }

    /**
     * 读取路径兜底：返回 TaskDraft（必要时 upgrader）。
     *
     * <p>{@code schemaVersion < 3} 的任务（V1 / V2）在内存中升 V3：V2 字段缺省已由
     * {@link TaskDefinition} 紧凑构造器填默认值，重建时仅替换 schemaVersion。
     * 本方法不写库；DB 层升级由启动 hook / writer 路径完成。
     *
     * <p>V1 LIST 任务若缺 {@code listItemRule}，readiness 会以
     * {@code LIST_ITEM_RULE_MISSING} 拒绝（运行时不允许起 run）。
     */
    public TaskDraft upgradeIfNeeded(TaskDraft draft) {
        if (draft == null || draft.schemaVersion() >= 3 || draft.schemaVersion() < 1) {
            // V3+ -> 原样返回（idempotent）；非法版本（<1）交由 readiness 拒绝。
            return draft;
        }
        return new TaskDraft(
                draft.id(), draft.ownerId(), draft.name(), draft.mode(), draft.status(),
                3, draft.version(), withSchemaVersion3(draft.definition()), draft.updatedAt());
    }

    /**
     * 写入路径兜底（M5 spec §D3 "writer 统一走 upgrader"）：
     * {@code schemaVersion < 3} 的定义在持久化前升 V3，保证 JSONB 内嵌值与列值一致。
     */
    public TaskDefinition upgradeIfNeeded(TaskDefinition definition) {
        if (definition == null || definition.schemaVersion() >= 3 || definition.schemaVersion() < 1) {
            return definition;
        }
        return withSchemaVersion3(definition);
    }

    /** 重建定义（仅替换 schemaVersion=3）；紧凑构造器对 null 字段填默认值。 */
    private static TaskDefinition withSchemaVersion3(TaskDefinition def) {
        return new TaskDefinition(3, def.mode(), def.startUrl(), def.viewport(),
                def.waitPolicy(), def.limits(), def.listItemRule(), def.uniqueKey(),
                def.paginationRule(), def.fields());
    }

    /** PostgreSQL {@code interval} 不接受 ISO-8601；用 PG 端 friendly 字符串。 */
    private static String formatDuration(java.time.Duration d) {
        long seconds = d.getSeconds();
        if (seconds % 60 != 0) {
            return "PT" + d.toMillis() + "S";
        }
        long minutes = seconds / 60;
        if (minutes == 0) {
            return "PT0S";
        }
        return "PT" + minutes + "M";
    }

    // Test helper：暴露默认 limits 形状（与 upgrader 内嵌值同步）。
    public static Limits defaults() {
        return Limits.globalDefault();
    }

    // Test helper：V1 LIST 任务判定（缺 listItemRule 即视为非法 DRAFT）。
    public static boolean isV1ListMissingItemRule(TaskDraft draft) {
        return draft != null
                && draft.schemaVersion() == 1
                && draft.mode() instanceof TaskMode.List
                && draft.definition().listItemRule() == null;
    }

    /** 仅暴露路由类型，便于 catalog 在 write 时升级（spec §D2 双层防护的 writer 路径）。 */
    public List<TaskDraft> listAll() {
        // 当前实现不在 JDBC 暴露该方法；保留 hook 给需要时扩展。
        return List.of();
    }
}
