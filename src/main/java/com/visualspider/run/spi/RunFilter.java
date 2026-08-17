package com.visualspider.run.spi;

/**
 * 列表过滤条件（M3 spec §D2）。
 *
 * <p>{@code status} 为可选过滤；{@code page}/{@code size} 由 caller 决定。
 */
public record RunFilter(
        RunState status,
        int page,
        int size) {
}