package com.visualspider.result.spi;

import com.visualspider.identity.domain.ActorId;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 运行结果流式导出 SPI（M3 spec §D13）。
 *
 * <p>{@link #writeCsv} / {@link #writeJson} 直接写 {@link OutputStream}
 * （典型调用方：{@code HttpServletResponse.getOutputStream()}），不生成磁盘临时文件
 * （architecture §3.6）。keyset 游标每批约 1000 行；10k 行不全量加载。
 *
 * <p>所有权：管理员全局；采集人员仅自己运行。非 owner 且非 admin 抛
 * {@link RunAccessDeniedException}。
 */
public interface RunExport {

    /**
     * 流式写入 CSV。
     *
     * <p>表头从运行固化快照（{@code collection_run.snapshot.definition.fields.name}）生成；
     * 每行按字段顺序输出 {@code cleanedValue}（空值输出空串）。
     *
     * @throws RunAccessDeniedException 非 owner 且非 admin
     * @throws IOException             写流出错
     */
    void writeCsv(long runId, ActorId actor, OutputStream out) throws IOException;

    /**
     * 流式写入 JSON 数组。
     *
     * <p>每个元素为 {@code ResultRecord.data} 字段映射；UTF-8；不缩进。
     *
     * @throws RunAccessDeniedException 非 owner 且非 admin
     * @throws IOException             写流出错
     */
    void writeJson(long runId, ActorId actor, OutputStream out) throws IOException;
}