/**
 * result 模块。
 *
 * <p>本包承载 result 业务能力：JSONB 结果行、结构化事件、按运行关联的分页查询、
 * CSV/JSON 流式导出、30 天保留与到期清理。
 *
 * <p>M3 启用；M0.5 仅完成包结构占位。
 *
 * <p>本包遵守 ADR-0003（按业务模块组织深模块）：
 * 仅通过稳定 interface 对外暴露能力；隐藏 PostgreSQL JSONB 写入、分页/流式游标、
 * 保留清理任务实现细节，禁止返回无界数组或一次性加载全部结果。
 */
package com.visualspider.result;