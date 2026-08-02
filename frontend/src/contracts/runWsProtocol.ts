/**
 * M3-5 #27 运行进度 WebSocket 协议 schema（schemaVersion: 1）。
 *
 * 服务端 -> 客户端：
 *   PROGRESS / EVENT / TERMINAL
 *
 * 客户端 -> 服务端：仅 CANCEL。
 * 入站 / 出站帧在边界用 {@link isValidFrame} 校验；schemaVersion !== 1 一律拒。
 */

import type { RunState, StopReason, RunEventLevel } from './run'

export const RUN_WS_SCHEMA_VERSION = 1 as const

export interface BaseFrame {
  schemaVersion: typeof RUN_WS_SCHEMA_VERSION
}

/**
 * 服务端状态推送（spec §D16）。
 * 字段顺序仅用于日志阅读；Jackson 序列化时按 record 顺序。
 */
export interface ProgressFrame extends BaseFrame {
  type: 'PROGRESS'
  status: RunState
  stopReason: StopReason | null
  stage: string | null
  currentUrl: string | null
  pageCount: number
  /** M3 单页下 == recordCountFinal；M4/M5 多页可与之分离。 */
  recordCountRaw: number
  recordCountFinal: number
  failCount: number
  /** 已累计耗时；启动前为 0。 */
  elapsedMs: number
}

export interface EventFrame extends BaseFrame {
  type: 'EVENT'
  id: number
  level: RunEventLevel
  stage: string | null
  url: string | null
  errorCode: string | null
  message: string
  /** epoch millis；服务端不写时为 0。 */
  createdAt: number
}

export interface TerminalFrame extends BaseFrame {
  type: 'TERMINAL'
  status: RunState
  stopReason: StopReason | null
  /** epoch millis；服务端不写时为 0。 */
  finishedAt: number
}

export type ServerFrame = ProgressFrame | EventFrame | TerminalFrame

/**
 * 客户端入站：CANCEL（CANCEL 重校所有权后等价 POST cancel）。
 */
export interface CancelFrame extends BaseFrame {
  type: 'CANCEL'
}

export type ClientFrame = CancelFrame

export function isValidServerFrame(obj: unknown): obj is ServerFrame {
  if (!obj || typeof obj !== 'object') {
    return false
  }
  const candidate = obj as { schemaVersion?: unknown; type?: unknown }
  if (candidate.schemaVersion !== RUN_WS_SCHEMA_VERSION) {
    return false
  }
  return (
    candidate.type === 'PROGRESS' ||
    candidate.type === 'EVENT' ||
    candidate.type === 'TERMINAL'
  )
}

export function isValidClientFrame(obj: unknown): obj is ClientFrame {
  if (!obj || typeof obj !== 'object') {
    return false
  }
  const candidate = obj as { schemaVersion?: unknown; type?: unknown }
  return candidate.schemaVersion === RUN_WS_SCHEMA_VERSION && candidate.type === 'CANCEL'
}

/**
 * 构造客户端 CANCEL 帧：服务端解析后重校所有权并触发 cancel。
 */
export function cancelFrame(): CancelFrame {
  return { schemaVersion: RUN_WS_SCHEMA_VERSION, type: 'CANCEL' }
}
