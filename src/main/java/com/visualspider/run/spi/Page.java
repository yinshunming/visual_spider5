package com.visualspider.run.spi;

import java.util.List;

/**
 * 通用分页结果（M3 spec §D17）。仅 M3-2 stub 使用；M3-3 result 模块会拓展
 * {@code RunResultQuery} 复用同形态。
 *
 * @param items 当前页条目
 * @param total 满足过滤条件的总数
 * @param page 0-based 页号
 * @param size 页大小
 */
public record Page<T>(
        List<T> items,
        long total,
        int page,
        int size) {

    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }
}