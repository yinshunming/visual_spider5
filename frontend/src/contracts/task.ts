/**
 * 任务契约（M3-6 #28 任务列表入口，仅消费）。
 *
 * 字段名与后端 {@code com.visualspider.task.domain.TaskSummary} 对应；
 * 前端不得手工修改字段名（OpenAPI 生成同步）。
 */

export const TASK_SUMMARY_SCHEMA_VERSION = 1 as const

export type TaskSummaryStatus = 'DRAFT' | 'READY'
export type TaskSummaryMode = 'SINGLE_PAGE' | 'LIST'

export interface TaskSummary {
  id: number
  name: string
  mode: TaskSummaryMode
  status: TaskSummaryStatus
  /** 乐观锁版本号；任务保存草稿时要求 expectedVersion 一致。 */
  version: number
  /** ISO-8601 字符串；M3 不展示精度，前端只显示日期。 */
  updatedAt: string
}
