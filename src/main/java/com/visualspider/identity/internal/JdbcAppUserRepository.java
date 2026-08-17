package com.visualspider.identity.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.domain.UserAccount;
import com.visualspider.identity.domain.UserStatus;
import com.visualspider.identity.spi.AppUserRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * {@link AppUserRepository} 的 JdbcTemplate 实现。
 *
 * <p>隐藏 JDBC / SQL 细节；service 层只接收领域对象。
 */
@Repository
public class JdbcAppUserRepository implements AppUserRepository {

    private static final RowMapper<UserAccount> ROW_MAPPER = (rs, rowNum) -> {
        String roleStr = rs.getString("role");
        ActorRole role = "ADMIN".equals(roleStr) ? new ActorRole.Admin() : new ActorRole.Collector();
        return new UserAccount(
                new ActorId(rs.getLong("id")),
                rs.getString("username"),
                role,
                UserStatus.valueOf(rs.getString("status")));
    };

    private final JdbcTemplate jdbc;

    public JdbcAppUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, username, role, status FROM app_user WHERE id = ?",
                    ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, username, role, status FROM app_user WHERE username = ?",
                    ROW_MAPPER, username));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> findPasswordHashByUsername(String username) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT password_hash FROM app_user WHERE username = ?",
                    String.class, username));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public long insert(String username, String passwordHash, ActorRole role, UserStatus status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO app_user (username, password_hash, role, status) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, role instanceof ActorRole.Admin ? "ADMIN" : "COLLECTOR");
            ps.setString(4, status.name());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("INSERT app_user 未返回生成键");
        }
        return key.longValue();
    }

    @Override
    public void updatePasswordHash(long userId, String newPasswordHash) {
        int rows = jdbc.update(
                "UPDATE app_user SET password_hash = ?, updated_at = now() WHERE id = ?",
                newPasswordHash, userId);
        if (rows == 0) {
            throw new IllegalStateException("updatePasswordHash 影响 0 行：userId=" + userId);
        }
    }

    @Override
    public void updateStatus(long userId, UserStatus newStatus) {
        int rows = jdbc.update(
                "UPDATE app_user SET status = ?, updated_at = now() WHERE id = ?",
                newStatus.name(), userId);
        if (rows == 0) {
            throw new IllegalStateException("updateStatus 影响 0 行：userId=" + userId);
        }
    }
}
