package com.visualspider.result.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.visualspider.task.domain.UniqueKeyField;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;

/**
 * 唯一键 hash 计算（M4 spec §D5）。
 *
 * <p>算法：
 * <ol>
 *   <li>提取 {@code finalValues} 中由 {@code keys} 指定的字段</li>
 *   <li>过滤掉 null / 空白值（视为该 record 不参与判重）</li>
 *   <li>按字段名排序（sorted canonical form），用 Jackson 序列化为稳定 JSON</li>
 *   <li>SHA-256 over JSON bytes</li>
 * </ol>
 *
 * <p>全部 keys 空或值全空 → 返回 null（不参与去重，但仍落盘）。
 */
public final class UniqueKeyHasher {

    private final ObjectMapper canonicalMapper;

    public UniqueKeyHasher() {
        // sorted-keys-by-name 实现：ObjectMapper 注册一个 sort-by-key Provider
        this.canonicalMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 计算 {@code keys} 字段对应值稳定 hash。
     *
     * @param keys        任务声明的唯一键字段列表（按 fieldName 取值）
     * @param finalValues 单条 record 已清洗的字段值映射
     * @return 32-byte SHA-256 digest；keys 缺失 / 任一空值都返回 null（spec §D8 全/部分空键视作不参与判重）
     */
    public byte[] hash(List<UniqueKeyField> keys, Map<String, String> finalValues) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        // 任一键缺失或值为 null/空白 → 整 record 不参与判重（spec §D8）
        for (UniqueKeyField k : keys) {
            if (k == null || k.fieldName() == null) {
                return null;
            }
            String value = finalValues == null ? null : finalValues.get(k.fieldName());
            if (value == null || value.isBlank()) {
                return null;
            }
        }
        // 全部键值非空：按 fieldName 排序后 canonical JSON + SHA-256
        var entries = keys.stream()
                .map(k -> new AbstractMap.SimpleEntry<>(k.fieldName(), finalValues.get(k.fieldName())))
                .sorted(Map.Entry.comparingByKey())
                .toList();
        try {
            String json = canonicalMapper.writeValueAsString(entries);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("uniqueKey hash 计算失败", ex);
        }
    }
}
