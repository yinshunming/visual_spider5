package com.visualspider.result.spi;

import java.util.List;

/**
 * 通用分页结果（M3 spec §D12 / D17）。
 *
 * <p>{@code items} 是当前页元素；{@code total} 是满足条件的总条数；
 * {@code page} / {@code size} 透传调用方入参，便于前端展示。
 */
public record Page<T>(
        List<T> items,
        int page,
        int size,
        long total) {

    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }
}