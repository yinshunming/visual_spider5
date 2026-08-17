package com.visualspider.task.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.visualspider.run.spi.RunLimits;
import java.time.Duration;

/**
 * 任务级运行限制（M4 spec §D1）。
 *
 * <p>采集人员可对单个任务覆盖三个上限；不填则走
 * {@link #globalDefault()}（= {@link RunLimits} 常量）。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 保证 schema 演化时
 * 旧 reader 可读新快照，未来扩展不需要 bump {@code schemaVersion}。
 *
 * <p>硬上限取自 {@link RunLimits}：{@code pageLimit ≤ MAX_PAGES}、
 * {@code recordLimit ≤ MAX_RECORDS}、{@code durationLimit ≤ MAX_DURATION}。
 * 越界由紧凑构造器拒绝；M6 入 {@code system_setting} 后只改读路径。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Limits(int pageLimit, int recordLimit, Duration durationLimit) {

    public Limits {
        if (pageLimit <= 0 || pageLimit > RunLimits.MAX_PAGES) {
            throw new IllegalArgumentException(
                    "pageLimit 必须在 1.." + RunLimits.MAX_PAGES + " 之间；got " + pageLimit);
        }
        if (recordLimit <= 0 || recordLimit > RunLimits.MAX_RECORDS) {
            throw new IllegalArgumentException(
                    "recordLimit 必须在 1.." + RunLimits.MAX_RECORDS + " 之间；got " + recordLimit);
        }
        if (durationLimit == null || durationLimit.isNegative()
                || durationLimit.compareTo(RunLimits.MAX_DURATION) > 0) {
            throw new IllegalArgumentException(
                    "durationLimit 必须在 0.." + RunLimits.MAX_DURATION + " 之间；got " + durationLimit);
        }
    }

    /** 全局默认：与 {@link RunLimits} 硬上限同值。 */
    public static Limits globalDefault() {
        return new Limits(RunLimits.MAX_PAGES, RunLimits.MAX_RECORDS, RunLimits.MAX_DURATION);
    }
}
