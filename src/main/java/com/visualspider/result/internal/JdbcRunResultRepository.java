package com.visualspider.result.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.result.spi.Page;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunAccessDeniedException;
import com.visualspider.result.spi.RunEvent;
import com.visualspider.result.spi.RunEventInput;
import com.visualspider.result.spi.RunEventLevel;
import com.visualspider.result.spi.RunEventQuery;
import com.visualspider.result.spi.RunResultQuery;
import com.visualspider.result.spi.RunResultSink;
import com.visualspider.result.spi.RunStats;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PGobject;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link RunResultSink} / {@link RunResultQuery} 的 JDBC 实现（spec §D12 / D13）。
 *
 * <p>负责：
 * <ul>
 *   <li>结果行 / 事件的批量写入（{@code run_result} + {@code run_event}）</li>
 *   <li>keyset 分页与汇总统计</li>
 *   <li>导出阶段的所有权校验、快照字段名读取、流式批量拉取</li>
 * </ul>
 *
 * <p>不引入新依赖；JSONB 用 PostgreSQL 驱动自带的 {@link PGobject}，反序列化复用
 * 注入的 {@link ObjectMapper}。流式导出辅助方法（{@link #fieldNames(long, ActorId)} 与
 * {@link #nextBatch(long, int, int)}）为 {@code internal} 包级私有，由 {@link CsvResultWriter} /
 * {@link JsonResultWriter} 间接调用。
 */
@Repository
public class JdbcRunResultRepository implements RunResultSink, RunResultQuery, RunEventQuery {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IdentityAccess identityAccess;

    public JdbcRunResultRepository(JdbcTemplate jdbc,
                                   ObjectMapper objectMapper,
                                   IdentityAccess identityAccess) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.identityAccess = identityAccess;
    }

    // ============================ 写入 ============================

    @Override
    public void appendBatch(long runId, List<ResultRecord> results, List<RunEventInput> events) {
        // 写入前确认 run 存在；sink 调用方为运行引擎，已在 RunCoordinator 校验权限
        Integer exists = jdbc.query(
                "SELECT 1 FROM collection_run WHERE id = ?",
                rs -> rs.next() ? 1 : null,
                runId);
        if (exists == null) {
            throw new RunAccessDeniedException(runId);
        }
        if (results != null && !results.isEmpty()) {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO run_result (run_id, sequence_no, data) VALUES (?, ?, ?::jsonb)");
                for (ResultRecord r : results) {
                    ps.setLong(1, runId);
                    ps.setInt(2, r.sequenceNo());
                    ps.setObject(3, toJsonb(r.data()));
                    ps.addBatch();
                }
                return ps;
            });
        }
        if (events != null && !events.isEmpty()) {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO run_event (run_id, level, stage, url, error_code, message) "
                                + "VALUES (?, ?, ?, ?, ?, ?)");
                for (RunEventInput e : events) {
                    ps.setLong(1, runId);
                    ps.setString(2, e.level().name());
                    ps.setString(3, e.stage());
                    ps.setString(4, e.url());
                    ps.setString(5, e.errorCode());
                    ps.setString(6, e.message());
                    ps.addBatch();
                }
                return ps;
            });
        }
    }

    // ============================ 查询 ============================

    private static final int MAX_PAGE_SIZE = 1000;

    @Override
    public Page<ResultRecord> page(long runId, ActorId actor, int page, int size) {
        verifyAccess(runId, actor);
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 1 : Math.min(size, MAX_PAGE_SIZE);
        int startSeq = (p - 1) * s;
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM run_result WHERE run_id = ?",
                Long.class, runId);
        List<ResultRecord> items = jdbc.query(
                "SELECT id, run_id, sequence_no, data, created_at FROM run_result "
                        + "WHERE run_id = ? AND sequence_no >= ? "
                        + "ORDER BY sequence_no ASC LIMIT ?",
                resultRowMapper(), runId, startSeq, s);
        return new Page<>(items, p, s, total == null ? 0L : total);
    }

    @Override
    public RunStats stats(long runId, ActorId actor) {
        verifyAccess(runId, actor);
        try {
            return jdbc.queryForObject(
                    "SELECT record_count_raw, record_count_dedup, record_count_final, fail_count "
                            + "FROM collection_run WHERE id = ?",
                    (rs, rn) -> new RunStats(
                            rs.getInt("record_count_raw"),
                            rs.getInt("record_count_dedup"),
                            rs.getInt("record_count_final"),
                            rs.getInt("fail_count")),
                    runId);
        } catch (EmptyResultDataAccessException e) {
            throw new RunAccessDeniedException(runId);
        }
    }

    // ============================ 导出辅助（internal） ============================

    /**
     * 校验调用方对指定运行有访问权；非 owner 且非 admin 抛 {@link RunAccessDeniedException}。
     * run 不存在也抛 {@link RunAccessDeniedException}（不回显存在性）。
     */
    void verifyAccess(long runId, ActorId actor) {
        Long ownerId;
        try {
            ownerId = jdbc.queryForObject(
                    "SELECT owner_id FROM collection_run WHERE id = ?",
                    Long.class, runId);
        } catch (EmptyResultDataAccessException e) {
            throw new RunAccessDeniedException(runId);
        }
        if (ownerId == null) {
            throw new RunAccessDeniedException(runId);
        }
        if (!identityAccess.canAccessTask(ownerId, actor)) {
            throw new RunAccessDeniedException(runId);
        }
    }

    /**
     * 从 {@code collection_run.snapshot} 反序列化 {@code TaskDefinition}，
     * 提取字段名列表（CSV 表头来源）。
     */
    List<String> fieldNames(long runId, ActorId actor) {
        verifyAccess(runId, actor);
        String json;
        try {
            json = jdbc.queryForObject(
                    "SELECT snapshot::text FROM collection_run WHERE id = ?",
                    String.class, runId);
        } catch (EmptyResultDataAccessException e) {
            throw new RunAccessDeniedException(runId);
        }
        if (json == null) {
            throw new RunAccessDeniedException(runId);
        }
        try {
            Map<String, Object> snapshot = objectMapper.readValue(json, Map.class);
            Object def = snapshot == null ? null : snapshot.get("definition");
            if (!(def instanceof Map<?, ?> defMap)) {
                return List.of();
            }
            Object fields = defMap.get("fields");
            if (!(fields instanceof List<?> list)) {
                return List.of();
            }
            List<String> names = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof Map<?, ?> fm) {
                    Object n = fm.get("name");
                    if (n instanceof String s && !s.isBlank()) {
                        names.add(s);
                    }
                }
            }
            return List.copyOf(names);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("run.snapshot 反序列化失败", e);
        }
    }

    /**
     * keyset 流式拉取一批 {@code run_result}。
     *
     * @param runId  运行 id
     * @param afterSeq 仅返回 {@code sequence_no > afterSeq} 的行；{@code -1} 表示从头开始
     * @param limit  本批上限
     * @return 本批结果（按 {@code sequence_no} 升序）；可能为空
     */
    List<ResultRecord> nextBatch(long runId, int afterSeq, int limit) {
        return jdbc.query(
                "SELECT id, run_id, sequence_no, data, created_at FROM run_result "
                        + "WHERE run_id = ? AND sequence_no > ? "
                        + "ORDER BY sequence_no ASC LIMIT ?",
                resultRowMapper(), runId, afterSeq, limit);
    }

    // ============================ 事件查询（M3-5 #27）============================

    @Override
    public Page<RunEvent> pageEvents(long runId, ActorId actor, int page, int size) {
        verifyAccess(runId, actor);
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 1 : Math.min(size, MAX_PAGE_SIZE);
        int offset = (p - 1) * s;
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM run_event WHERE run_id = ?",
                Long.class, runId);
        List<RunEvent> items = jdbc.query(
                "SELECT id, run_id, level, stage, url, error_code, message, created_at "
                        + "FROM run_event WHERE run_id = ? "
                        + "ORDER BY id ASC LIMIT ? OFFSET ?",
                eventRowMapper(), runId, s, offset);
        return new Page<>(items, p, s, total == null ? 0L : total);
    }

    @Override
    public List<RunEvent> after(long runId, ActorId actor, long afterEventId) {
        verifyAccess(runId, actor);
        long startId = afterEventId <= 0 ? 0L : afterEventId;
        return jdbc.query(
                "SELECT id, run_id, level, stage, url, error_code, message, created_at "
                        + "FROM run_event WHERE run_id = ? AND id > ? "
                        + "ORDER BY id ASC LIMIT 1000",
                eventRowMapper(), runId, startId);
    }

    private RowMapper<RunEvent> eventRowMapper() {
        return (rs, rowNum) -> new RunEvent(
                rs.getLong("id"),
                rs.getLong("run_id"),
                RunEventLevel.valueOf(rs.getString("level")),
                rs.getString("stage"),
                rs.getString("url"),
                rs.getString("error_code"),
                rs.getString("message"),
                rs.getTimestamp("created_at") == null ? null
                        : rs.getTimestamp("created_at").toInstant());
    }

    // ============================ RowMappers (instance) ============================

    private RowMapper<ResultRecord> resultRowMapper() {
        ObjectMapper mapper = this.objectMapper;
        return (rs, rowNum) -> {
            String json = rs.getString("data");
            Map<String, String> data;
            try {
                data = parseJsonbStringMap(mapper, json);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("run_result.data 反序列化失败", e);
            }
            Timestamp ts = rs.getTimestamp("created_at");
            Instant createdAt = ts == null ? null : ts.toInstant();
            return new ResultRecord(
                    rs.getLong("id"),
                    rs.getLong("run_id"),
                    rs.getInt("sequence_no"),
                    data,
                    createdAt);
        };
    }

    // ============================ helpers ============================

    private static Map<String, String> parseJsonbStringMap(ObjectMapper mapper, String json)
            throws JsonProcessingException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw = mapper.readValue(json, Map.class);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            out.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().toString());
        }
        return out;
    }

    private PGobject toJsonb(Map<String, String> data) {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException | SQLException e) {
            throw new IllegalArgumentException("ResultRecord.data 序列化失败", e);
        }
        return obj;
    }
}