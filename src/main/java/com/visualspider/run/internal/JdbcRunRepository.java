package com.visualspider.run.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.run.spi.Page;
import com.visualspider.run.spi.RunDetail;
import com.visualspider.run.spi.RunFilter;
import com.visualspider.run.spi.RunProgress;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.RunSummary;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.TaskSnapshot;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.postgresql.util.PGobject;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@link RunRepository} 的 JdbcTemplate 实现（M3-2 #24 / spec §D5）。
 *
 * <p>封装所有 {@code collection_run} 访问；snapshot 通过 Jackson 序列化为 JSONB。
 *
 * <p>CAS claim：{@code UPDATE collection_run SET status='RUNNING', started_at=now()
 * WHERE id=? AND status='WAITING'}；affected=1 才提交到 lane；affected=0 表示
 * 该 run 已被别处取走（单 JVM 下不会发生，但防御性 break）。
 */
@Repository
public class JdbcRunRepository implements RunRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public int countActiveByOwner(long ownerId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM collection_run WHERE owner_id = ? "
                        + "AND status IN ('WAITING','RUNNING')",
                Integer.class, ownerId);
        return n == null ? 0 : n;
    }

    @Override
    public long insertWaiting(long taskId, long ownerId, TaskSnapshot snapshot) {
        PGobject jsonb = toJsonb(snapshot);
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO collection_run (task_id, owner_id, snapshot, status) "
                            + "VALUES (?, ?, ?::jsonb, 'WAITING')",
                    new String[]{"id"});
            ps.setLong(1, taskId);
            ps.setLong(2, ownerId);
            ps.setObject(3, jsonb);
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalStateException("INSERT collection_run 未返回生成键");
        }
        return key.longValue();
    }

    @Override
    public Optional<RunRecord> findById(long runId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, task_id, owner_id, status, stop_reason, cancel_requested, "
                            + "page_count, record_count_final, fail_count, snapshot, "
                            + "created_at, started_at, finished_at "
                            + "FROM collection_run WHERE id = ?",
                    recordMapper(), runId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RunRecord> claimOldestWaiting() {
        // CAS：先取最旧 WAITING 的 id，再 UPDATE 翻 RUNNING；affected=0 视为已被取走
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM collection_run WHERE status='WAITING' "
                        + "ORDER BY created_at ASC LIMIT 1",
                Long.class);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        long id = ids.get(0);
        int updated = jdbc.update(
                "UPDATE collection_run SET status='RUNNING', started_at=now() "
                        + "WHERE id=? AND status='WAITING'",
                id);
        if (updated != 1) {
            return Optional.empty();
        }
        return findById(id);
    }

    @Override
    public boolean markCancelRequested(long runId) {
        int rows = jdbc.update(
                "UPDATE collection_run SET cancel_requested=true WHERE id=? "
                        + "AND status IN ('WAITING','RUNNING')",
                runId);
        return rows == 1;
    }

    @Override
    public boolean markTerminal(long runId, RunState status, StopReason stopReason) {
        int rows = jdbc.update(
                "UPDATE collection_run SET status=?, stop_reason=?, finished_at=now() "
                        + "WHERE id=? AND status IN ('WAITING','RUNNING')",
                status.name(), stopReason == null ? null : stopReason.name(), runId);
        return rows == 1;
    }

    @Override
    public int markAllActiveInterrupted() {
        return jdbc.update(
                "UPDATE collection_run SET status='INTERRUPTED', stop_reason='APP_INTERRUPTED', "
                        + "finished_at=now() WHERE status IN ('WAITING','RUNNING')");
    }

    @Override
    public List<RunSummary> listByOwner(Long ownerId, RunFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, task_id, owner_id, status, stop_reason, cancel_requested, "
                        + "page_count, record_count_final, fail_count, "
                        + "created_at, started_at, finished_at FROM collection_run ");
        List<Object> args = new ArrayList<>();
        if (ownerId != null) {
            sql.append("WHERE owner_id = ? ");
            args.add(ownerId);
        } else {
            sql.append("WHERE 1=1 ");
        }
        if (filter != null && filter.status() != null) {
            sql.append("AND status = ? ");
            args.add(filter.status().name());
        }
        sql.append("ORDER BY created_at DESC");
        return jdbc.query(sql.toString(), summaryMapper(), args.toArray());
    }

    @Override
    public Page<RunSummary> pageByOwner(Long ownerId, RunFilter filter) {
        List<RunSummary> all = listByOwner(ownerId, filter);
        int page = filter == null ? 0 : Math.max(0, filter.page());
        int size = filter == null ? 50 : Math.max(1, filter.size());
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return new Page<>(all.subList(from, to), all.size(), page, size);
    }

    @Override
    public Optional<RunProgress> loadProgress(long runId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT status, stop_reason, page_count, record_count_final, "
                            + "fail_count, started_at FROM collection_run WHERE id = ?",
                    progressMapper(), runId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RunDetail> loadDetail(long runId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, task_id, owner_id, status, stop_reason, cancel_requested, "
                            + "page_count, record_count_raw, record_count_dedup, record_count_final, "
                            + "fail_count, current_url, stage, "
                            + "created_at, started_at, finished_at, snapshot "
                            + "FROM collection_run WHERE id = ?",
                    detailMapper(), runId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ---------- mappers ----------

    private RowMapper<RunRecord> recordMapper() {
        return (rs, rowNum) -> {
            try {
                TaskSnapshot snap = objectMapper.readValue(rs.getString("snapshot"),
                        TaskSnapshot.class);
                return new RunRecord(
                        rs.getLong("id"),
                        rs.getLong("task_id"),
                        rs.getLong("owner_id"),
                        RunState.valueOf(rs.getString("status")),
                        readStopReason(rs.getString("stop_reason")),
                        rs.getBoolean("cancel_requested"),
                        rs.getInt("page_count"),
                        rs.getInt("record_count_final"),
                        rs.getInt("fail_count"),
                        snap,
                        toOffset(rs.getTimestamp("created_at")),
                        toOffset(rs.getTimestamp("started_at")),
                        toOffset(rs.getTimestamp("finished_at")));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("snapshot JSON 反序列化失败", e);
            }
        };
    }

    private RowMapper<RunSummary> summaryMapper() {
        return (rs, rowNum) -> new RunSummary(
                rs.getLong("id"),
                rs.getLong("task_id"),
                rs.getLong("owner_id"),
                RunState.valueOf(rs.getString("status")),
                readStopReason(rs.getString("stop_reason")),
                rs.getBoolean("cancel_requested"),
                rs.getInt("page_count"),
                rs.getInt("record_count_final"),
                rs.getInt("fail_count"),
                toOffset(rs.getTimestamp("created_at")),
                toOffset(rs.getTimestamp("started_at")),
                toOffset(rs.getTimestamp("finished_at")));
    }

    private RowMapper<RunProgress> progressMapper() {
        return (rs, rowNum) -> new RunProgress(
                RunState.valueOf(rs.getString("status")),
                readStopReason(rs.getString("stop_reason")),
                null, null,
                rs.getInt("page_count"),
                rs.getInt("record_count_final"),
                rs.getInt("record_count_final"),
                rs.getInt("fail_count"),
                0L);
    }

    private RowMapper<RunDetail> detailMapper() {
        return (rs, rowNum) -> {
            try {
                TaskSnapshot snap = objectMapper.readValue(rs.getString("snapshot"),
                        TaskSnapshot.class);
                return new RunDetail(
                        rs.getLong("id"),
                        rs.getLong("task_id"),
                        rs.getLong("owner_id"),
                        RunState.valueOf(rs.getString("status")),
                        readStopReason(rs.getString("stop_reason")),
                        rs.getBoolean("cancel_requested"),
                        rs.getInt("page_count"),
                        rs.getInt("record_count_raw"),
                        rs.getInt("record_count_dedup"),
                        rs.getInt("record_count_final"),
                        rs.getInt("fail_count"),
                        rs.getString("current_url"),
                        rs.getString("stage"),
                        toOffset(rs.getTimestamp("created_at")),
                        toOffset(rs.getTimestamp("started_at")),
                        toOffset(rs.getTimestamp("finished_at")),
                        new RunDetail.TaskSnapshotMeta(snap.name(), snap.mode(), snap.schemaVersion(),
                                snap.version(), snap.definition()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("snapshot JSON 反序列化失败", e);
            }
        };
    }

    private static StopReason readStopReason(String s) {
        return s == null ? null : StopReason.valueOf(s);
    }

    private static OffsetDateTime toOffset(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private PGobject toJsonb(Object value) {
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("snapshot 序列化失败", e);
        }
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(json);
        } catch (SQLException e) {
            throw new IllegalArgumentException("PGobject setValue 失败", e);
        }
        return obj;
    }
}