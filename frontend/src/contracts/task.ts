/**
 * 任务契约（M3-6 #28 任务列表入口，仅消费；M4-6 #36 增 TaskDraft）。
 *
 * 字段名与后端 {@code com.visualspider.task.domain.TaskSummary} /
 * {@code com.visualspider.task.domain.TaskDraft} 对应；
 * 前端不得手工修改字段名（OpenAPI 生成同步）。
 */

import type { TaskDefinition } from './visualSession'

export const TASK_SUMMARY_SCHEMA_VERSION = 1 as const
export const TASK_DRAFT_SCHEMA_VERSION = 2 as const

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

/**
 * M4-6 #36：任务完整快照（{@code GET /api/tasks/{id}}）。
 * VisualSessionView 用此拿到 mode + definition 以决定挂载单页或列表 UI。
 */
export interface TaskDraft {
  id: number
  ownerId: number
  name: string
  mode: TaskSummaryMode
  status: TaskSummaryStatus
  schemaVersion: typeof TASK_DRAFT_SCHEMA_VERSION
  version: number
  definition: TaskDefinition
  updatedAt: string
}
