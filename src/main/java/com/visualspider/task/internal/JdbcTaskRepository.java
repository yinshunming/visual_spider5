package com.visualspider.task.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskStatus;
import com.visualspider.task.domain.TaskSummary;
import com.visualspider.task.spi.TaskRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@link TaskRepository} 的 JdbcTemplate 实现。
 *
 * <p>定义通过 Jackson 序列化为 JSONB；读取时反序列化。
 */
@Repository
public class JdbcTaskRepository implements TaskRepository {

    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<TaskSummary> summaryMapper;
    private final RowMapper<TaskDraft> draftMapper;

    public JdbcTaskRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.summaryMapper = (rs, rowNum) -> new TaskSummary(
                rs.getLong("id"),
                rs.getString("name"),
                readMode(rs.getString("mode")),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getLong("version"),
                toOffset(rs.getTimestamp("updated_at")));
        this.draftMapper = (rs, rowNum) -> {
            try {
                TaskDefinition def = objectMapper.readValue(rs.getString("definition"), TaskDefinition.class);
                return new TaskDraft(
                        rs.getLong("id"),
                        rs.getLong("owner_id"),
                        rs.getString("name"),
                        readMode(rs.getString("mode")),
                        TaskStatus.valueOf(rs.getString("status")),
                        rs.getInt("schema_version"),
                        rs.getLong("version"),
                        def,
                        toOffset(rs.getTimestamp("updated_at")));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("task.definition JSON 反序列化失败", e);
            }
        };
    }

    private TaskMode readMode(String s) {
        return "LIST".equals(s) ? new TaskMode.List() : new TaskMode.SinglePage();
    }

    private String writeMode(TaskMode mode) {
        return mode instanceof TaskMode.List ? "LIST" : "SINGLE_PAGE";
    }

    private static OffsetDateTime toOffset(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    @Override
    public long insert(long ownerId, String name, TaskDefinition definition) {
        String json;
        try {
            json = objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("definition 序列化失败", e);
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ownerId);
            ps.setString(2, name);
            ps.setString(3, writeMode(definition.mode()));
            ps.setString(4, TaskStatus.DRAFT.name());
            ps.setInt(5, CURRENT_SCHEMA_VERSION);
            ps.setString(6, json);
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalStateException("INSERT collection_task 未返回生成键");
        }
        return key.longValue();
    }

    @Override
    public Optional<TaskDraft> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, owner_id, name, mode, status, schema_version, version, definition, updated_at "
                            + "FROM collection_task WHERE id = ?",
                    draftMapper, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<TaskSummary> listByOwner(Long ownerId) {
        if (ownerId == null) {
            return jdbc.query(
                    "SELECT id, name, mode, status, version, updated_at FROM collection_task "
                            + "ORDER BY updated_at DESC",
                    summaryMapper);
        }
        return jdbc.query(
                "SELECT id, name, mode, status, version, updated_at FROM collection_task "
                        + "WHERE owner_id = ? ORDER BY updated_at DESC",
                summaryMapper, ownerId);
    }

    @Override
    public boolean updateDraft(long id, TaskDefinition definition, long expectedVersion) {
        String json;
        try {
            json = objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("definition 序列化失败", e);
        }
        int rows = jdbc.update(
                "UPDATE collection_task SET definition = ?, mode = ?, schema_version = ?, "
                        + "version = version + 1, updated_at = now() "
                        + "WHERE id = ? AND version = ?",
                json, writeMode(definition.mode()), CURRENT_SCHEMA_VERSION, id, expectedVersion);
        return rows == 1;
    }

    @Override
    public boolean deleteById(long id, long expectedOwnerId) {
        int rows = jdbc.update(
                "DELETE FROM collection_task WHERE id = ? AND owner_id = ?",
                id, expectedOwnerId);
        return rows == 1;
    }
}
