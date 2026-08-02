package com.visualspider.result.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.task.domain.UniqueKeyField;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UniqueKeyHasher} 单元测试（M4 spec §D5）。
 */
class UniqueKeyHasherTest {

    private final UniqueKeyHasher hasher = new UniqueKeyHasher();

    @Test
    @DisplayName("null keys → null")
    void nullKeysReturnsNull() {
        byte[] h = hasher.hash(null, Map.of("k", "v"));
        assertThat(h).isNull();
    }

    @Test
    @DisplayName("empty keys → null")
    void emptyKeysReturnsNull() {
        byte[] h = hasher.hash(List.of(), Map.of("k", "v"));
        assertThat(h).isNull();
    }

    @Test
    @DisplayName("全部值为 null → null（不参与判重）")
    void allNullValues() {
        UniqueKeyField k = new UniqueKeyField("k");
        byte[] h = hasher.hash(List.of(k), new HashMap<>());
        assertThat(h).isNull();
    }

    @Test
    @DisplayName("部分值为 null → null（部分空键视作不参与判重）")
    void partialNullValues() {
        UniqueKeyField k1 = new UniqueKeyField("a");
        UniqueKeyField k2 = new UniqueKeyField("b");
        Map<String, String> values = new HashMap<>();
        values.put("a", "x");
        // b 缺失
        byte[] h = hasher.hash(List.of(k1, k2), values);
        assertThat(h).isNull();
    }

    @Test
    @DisplayName("稳定键值对 → 同一 hash")
    void stableHash() {
        UniqueKeyField k1 = new UniqueKeyField("title");
        Map<String, String> values1 = Map.of("title", "Hello", "url", "http://x");
        Map<String, String> values2 = new HashMap<>();
        values2.put("title", "Hello");
        values2.put("url", "http://x");
        byte[] h1 = hasher.hash(List.of(k1), values1);
        byte[] h2 = hasher.hash(List.of(k1), values2);
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(32);
    }

    @Test
    @DisplayName("不同键值对 → 不同 hash")
    void differentHash() {
        UniqueKeyField k = new UniqueKeyField("title");
        byte[] h1 = hasher.hash(List.of(k), Map.of("title", "Hello"));
        byte[] h2 = hasher.hash(List.of(k), Map.of("title", "World"));
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("键顺序不影响 hash（canonical sorted）")
    void keyOrderDoesNotAffectHash() {
        UniqueKeyField k1 = new UniqueKeyField("a");
        UniqueKeyField k2 = new UniqueKeyField("b");
        Map<String, String> values = Map.of("a", "1", "b", "2");
        byte[] h1 = hasher.hash(List.of(k1, k2), values);
        byte[] h2 = hasher.hash(List.of(k2, k1), values);
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("空字符串值视为 null（不判重）")
    void blankValueRejected() {
        UniqueKeyField k = new UniqueKeyField("title");
        byte[] h = hasher.hash(List.of(k), Map.of("title", "   "));
        assertThat(h).isNull();
    }
}
