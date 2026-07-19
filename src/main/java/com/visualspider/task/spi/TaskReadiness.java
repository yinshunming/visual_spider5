package com.visualspider.task.spi;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.TaskDefinition;

/**
 * 任务校验 SPI。
 *
 * <p>M1-3 落地 {@link #validate(TaskDefinition)} 最小集（spec §D4 / T1）；
 * {@link #validateForRun(long, ActorId)} M3 启用。
 */
public interface TaskReadiness {

    /**
     * 最小集校验：
     * <ul>
     *   <li>schemaVersion == 1</li>
     *   <li>mode ∈ {SINGLE_PAGE, LIST}</li>
     *   <li>startUrl 为合法 http(s) URL（URI.create + scheme + host 非空；M1 不做 SSRF 完整校验）</li>
     *   <li>viewport == 1280x720（其它值拒绝并提示 M2 启用）</li>
     *   <li>fields 列表字段名非空且唯一</li>
     * </ul>
     */
    ReadinessReport validate(TaskDefinition draft);

    /**
     * 运行前校验。M3 启用；M1-3 抛 {@code UnsupportedOperationException("M3 启用")}。
     */
    ReadinessReport validateForRun(long taskId, ActorId actor);
}
