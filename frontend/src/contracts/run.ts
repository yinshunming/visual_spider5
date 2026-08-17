/**
 * M3-5 #27 REST 契约（schemaVersion: 1）。
 *
 * 字段名与后端 Java record 对应；前端不得手工修改字段名（OpenAPI 生成同步）。
 * 已有 {@link TaskDefinition} 复用 `visualSession.ts` 定义。
 */

import type { TaskDefinition } from './visualSession'

export const RUN_SCHEMA_VERSION = 1 as const

/**
 * 运行状态枚举（与 {@code com.visualspider.run.spi.RunState} 一一对应）。
 * 服务端写死 7 态；前端只关心可见的子集。
 */
export type RunState =
  | 'WAITING'
  | 'RUNNING'
  | 'SUCCESS'
  | 'PARTIAL_SUCCESS'
  | 'FAILED'
  | 'CANCELLED'
  | 'INTERRUPTED'

/**
 * 停止原因（与 {@code com.visualspider.run.spi.StopReason} 对应）。
 * 前端只读不写；不存在 stopReason 时为 null（终态未达或 WAITING）。
 */
export type StopReason =
  | 'COMPLETED'
  | 'USER_CANCEL'
  | 'ENTRY_FAILED'
  | 'BROWSER_START_FAILED'
  | 'PAGE_RETRY_EXHAUSTED'
  | 'PAGE_LIMIT'
  | 'RECORD_LIMIT'
  | 'TIME_LIMIT'
  | 'HTTP_429'
  | 'HTTP_403'
  | 'CAPTCHA'
  | 'APP_INTERRUPTED'

/** 列表/详情共用 summary（spec §D17）。 */
export interface RunSummary {
  runId: number
  taskId: number
  ownerId: number
  status: RunState
  stopReason: StopReason | null
  cancelRequested: boolean
  pageCount: number
  recordCountRaw: number
  recordCountDedup: number
  recordCountFinal: number
  failCount: number
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface RunStartRequest {
  /** 必须为正整数。 */
  taskId: number
}

export interface RunStartResponse {
  runId: number
  status: RunState
  createdAt: string
}

export interface RunListResponse {
  items: RunSummary[]
  total: number
  page: number
  size: number
}

/** 详情（spec §D17）。含 snapshot meta。 */
export interface RunSnapshotMeta {
  name: string
  mode: 'SINGLE_PAGE' | 'LIST'
  schemaVersion: typeof RUN_SCHEMA_VERSION
  taskVersion: number
  definition: TaskDefinition
}

export interface RunDetail extends RunSummary {
  recordCountRaw: number
  recordCountDedup: number
  currentUrl: string | null
  stage: string | null
  snapshotMeta: RunSnapshotMeta
}

/** 单条结果记录（与 {@code com.visualspider.result.spi.ResultRecord} 对应）。 */
export interface ResultRecord {
  id: number
  runId: number
  sequenceNo: number
  data: Record<string, string>
  createdAt: string | null
}

export interface RunResultsResponse {
  items: ResultRecord[]
  total: number
  page: number
  size: number
}

/** 快照查看（spec §D17）。 */
export interface RunSnapshotResponse {
  runId: number
  taskId: number
  name: string
  mode: 'SINGLE_PAGE' | 'LIST'
  schemaVersion: typeof RUN_SCHEMA_VERSION
  taskVersion: number
  definition: TaskDefinition
}

/** 事件级别（与 {@code com.visualspider.result.spi.RunEventLevel} 对应）。 */
export type RunEventLevel = 'INFO' | 'WARN' | 'ERROR'

/** 事件读取 DTO（spec §D17）。 */
export interface RunEventDto {
  id: number
  runId: number
  level: RunEventLevel
  stage: string | null
  url: string | null
  errorCode: string | null
  message: string
  createdAt: string | null
}

export interface RunEventsResponse {
  items: RunEventDto[]
  total: number
  page: number
  size: number
}

/** 导出参数。 */
export type ExportFormat = 'csv' | 'json'
