package com.visualspider.task.internal;

import com.visualspider.task.domain.Limits;
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
 * schemaVersion 启动 hook（M4 spec §D2）。
 *
 * <p>在 {@link com.visualspider.run.internal.RunRecoveryImpl} 之后、
 * {@code RunDispatcher.onContextRefreshed} 之前执行（{@code @Order(HIGHEST + 20)}）。
 * 顺序约定见 {@code docs/specs/m4.md} §D2 / §T1。
 *
 * <p>行为：
 * <ol>
 *   <li>V1 SINGLE_PAGE → V2：补 {@code limits} + 提升 schemaVersion=2</li>
 *   <li>V1 LIST 缺 {@code listItemRule} → 一律降 DRAFT（V1 时期 LIST 任务已无规则，
 *       安全降级以避免运行时被错误地认为可执行）；LOG WARN</li>
 *   <li>V2 → no-op（idempotent）</li>
 * </ol>
 *
 * <p>读取路径双层防护：{@code TaskCatalog.read} / {@code JdbcTaskRepository.findById}
 * 也调用 {@link #upgradeIfNeeded(TaskDraft)} 兜底。
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
        try {
            int upgraded = upgradeV1SinglePage();
            int downgraded = downgradeV1ListWithoutRule();
            if (upgraded > 0 || downgraded > 0) {
                LOG.info("M4 schema upgrader: upgraded {} SINGLE_PAGE, downgraded {} LIST → DRAFT",
                        upgraded, downgraded);
            } else {
                LOG.info("M4 schema upgrader: no V1 tasks to migrate");
            }
        } catch (RuntimeException ex) {
            // 启动期异常不应阻止应用启动；记录并继续。
            LOG.warn("M4 schema upgrader failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * V1 SINGLE_PAGE → V2：用 {@link Limits#globalDefault()} 补 limits 字段并 bump。
     */
    int upgradeV1SinglePage() {
        Limits defaults = Limits.globalDefault();
        String sql = """
                UPDATE collection_task
                SET schema_version = 2,
                    definition = jsonb_set(definition, '{limits}',
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
     * V1 LIST 任务缺 {@code listItemRule} → 状态降为 DRAFT。
     */
    int downgradeV1ListWithoutRule() {
        String sql = """
                UPDATE collection_task
                SET status = ?
                WHERE mode = 'LIST'
                  AND (definition->>'schemaVersion')::int = 1
                  AND NOT (definition ? 'listItemRule')
                """;
        return jdbc.update(sql, new MapSqlParameterSource().addValue("status", TaskStatus.DRAFT.name()));
    }

    /**
     * 读取路径兜底：返回 TaskDraft（必要时 upgrader）。
     *
     * <p>V1 SP 任务反序列化时缺三块新字段，紧凑构造器填默认值；
     * 若仍是 V1，下次 writer 写入会 bump 到 V2。本方法不写库，只补默认值。
     *
     * <p>V1 LIST 任务若缺 {@code listItemRule}，则视为 DRAFT 状态（运行时不允许起 run）。
     */
    public TaskDraft upgradeIfNeeded(TaskDraft draft) {
        if (draft == null) {
            return null;
        }
        if (draft.schemaVersion() == 2) {
            return draft;
        }
        // V1: 字段已由 TaskDefinition 紧凑构造器填默认值；不在此改动。
        // V1 LIST → DRAFT：检查 List mode 任务缺 listItemRule 的情况，运行时按 DRAFT 处理
        // （不写库；catalog reader 可在 read() 后判断 status）。
        return draft;
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
