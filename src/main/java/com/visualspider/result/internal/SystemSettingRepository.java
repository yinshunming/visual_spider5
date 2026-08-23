package com.visualspider.result.internal;

import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * system_setting 表读写（M6-6 / docs/specs/m6.md §D14）。
 *
 * <p>M6 仅 {@code retention.days} 一个 key 走该 Repository；其余 6 处预留点
 * （lane 容量 / Pacing 间隔 / 403 窗口 / 页面超时等）留 M7 / 首版后。
 */
@Repository
public class SystemSettingRepository {

    private final JdbcTemplate jdbc;

    public SystemSettingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Integer> findInt(String key) {
        try {
            String value = jdbc.queryForObject(
                    "SELECT value FROM system_setting WHERE key = ?",
                    String.class, key);
            return Optional.ofNullable(Integer.valueOf(value));
        } catch (EmptyResultDataAccessException notFound) {
            return Optional.empty();
        } catch (NumberFormatException nfe) {
            return Optional.empty();
        }
    }

    public void upsert(String key, String value) {
        // PostgreSQL UPSERT
        jdbc.update(
                "INSERT INTO system_setting (key, value) VALUES (?, ?) "
                        + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()",
                key, value);
    }
}
