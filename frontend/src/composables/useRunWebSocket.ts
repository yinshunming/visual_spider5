/**
 * M3-6 #28 详情 WebSocket composable（spec §D16 / §D18）。
 *
 * 职责：
 *   - 建立到 {@code /ws/runs/{runId}?csrf=...} 的连接；
 *   - 在协议层强制 schemaVersion=1，丢弃任何不符的帧；
 *   - 把 PROGRESS / EVENT / TERMINAL 写入 ref，让上层视图直接绑定；
 *   - 收到 TERMINAL 自动 close，WS 异常关闭时把 fallback 翻 true 让上层走 REST 轮询；
 *   - 仅暴露 CANCEL 出站；
 *   - 卸载时主动 close，不挂回调。
 *
 * 设计上把 {@link SocketLike} 抽出来供测试注入（避免 jsdom 的 ws shim 差异）。
 */

import { onBeforeUnmount, ref, type Ref } from 'vue'
import type { RunDetail, RunEventDto } from '../contracts/run'
import {
  RUN_WS_SCHEMA_VERSION,
  cancelFrame,
  isValidServerFrame,
  type CancelFrame,
  type ProgressFrame,
  type EventFrame,
  type TerminalFrame,
} from '../contracts/runWsProtocol'

/** 浏览器原生 WebSocket 形状的最小子集，便于测试用 fake 替换。 */
export interface SocketLike {
  readonly url: string
  readyState: number
  onopen: (() => void) | null
  onmessage: ((payload: string) => void) | null
  onclose: (() => void) | null
  onerror: (() => void) | null
  send(payload: string): void
  close(): void
}

export interface UseRunWebSocketOptions {
  runId: number
  /** 通常是 {@code location.origin}；测试时可注入。 */
  baseHref: string
  /** 默认是浏览器原生 {@code new WebSocket}。 */
  socketFactory?: (url: string) => SocketLike
  /** 取 CSRF token；默认从 {@code XSRF-TOKEN} cookie 读。 */
  readCsrf?: () => string | null
  /** 触发 cancel 时同时通知上层（REST 取消幂等可以不发，但需要通知 UI）。 */
  onCancel?: () => void
  /** WS 断开时的 fallback REST 拉取；上层 View 把它挂到轮询上。 */
  fallbackRest?: () => Promise<RunDetail> | null
  /** 仅协议：WS 开帧延迟多少 ms 才落 fallback（默认 0，立即）。 */
  openGraceMs?: number
}

export interface UseRunWebSocketResult {
  connected: Ref<boolean>
  fallback: Ref<boolean>
  progress: Ref<ProgressFrame | null>
  events: Ref<EventFrame[]>
  terminal: Ref<TerminalFrame | null>
  cancel: () => void
  close: () => void
}

export function useRunWebSocket(options: UseRunWebSocketOptions): UseRunWebSocketResult {
  const socketFactory = options.socketFactory ?? defaultSocketFactory
  const readCsrf = options.readCsrf ?? defaultReadCsrf

  const connected = ref(false)
  const fallback = ref(false)
  const progress = ref<ProgressFrame | null>(null)
  const events = ref<EventFrame[]>([])
  const terminal = ref<TerminalFrame | null>(null)

  let stopped = false
  let socket: SocketLike | null = null

  function buildUrl(): string {
    const token = readCsrf() ?? ''
    const protocol = options.baseHref.startsWith('https')
      ? 'wss'
      : options.baseHref.startsWith('http')
        ? 'ws'
        : 'ws'
    const origin = options.baseHref.replace(/^https?/, protocol)
    return `${origin}/ws/runs/${options.runId}?csrf=${encodeURIComponent(token)}`
  }

  function attach(): void {
    if (stopped) {
      return
    }
    const s = socketFactory(buildUrl())
    socket = s
    s.onopen = () => {
      if (stopped) {
        return
      }
      connected.value = true
      fallback.value = false
    }
    s.onmessage = (raw: string) => {
      if (stopped) {
        return
      }
      let parsed: unknown
      try {
        parsed = JSON.parse(raw)
      } catch {
        return
      }
      // 协议层 schemaVersion 校验：与 contracts/runWsProtocol 一致，丢弃任何版本不符的帧。
      if (!isValidServerFrame(parsed)) {
        return
      }
      if (parsed.type === 'PROGRESS') {
        progress.value = parsed
      } else if (parsed.type === 'EVENT') {
        events.value = [...events.value, parsed]
      } else if (parsed.type === 'TERMINAL') {
        terminal.value = parsed
        // 终态到达：让上层停止 fallback 轮询，关闭 socket。
        close()
      }
    }
    s.onclose = () => {
      if (stopped) {
        return
      }
      connected.value = false
      // 只有不是终态导致的关闭才回退到 REST 轮询。
      if (terminal.value === null) {
        fallback.value = true
      }
    }
    s.onerror = () => {
      // 同 onclose：异常也走 fallback；浏览器 WebSocket 触发 error 几乎总是伴随 close。
      if (stopped) {
        return
      }
      if (terminal.value === null) {
        fallback.value = true
      }
    }
  }

  function close(): void {
    if (socket !== null) {
      try {
        socket.close()
      } catch {
        // noop：close 抛错忽略，避免卸载时炸
      }
      socket = null
    }
    connected.value = false
  }

  function cancel(): void {
    // 发送合法 CANCEL 帧；server 端会重校所有权，等价于 POST cancel。
    const frame: CancelFrame = cancelFrame()
    if (socket !== null && socket.readyState === 1 /* OPEN */) {
      socket.send(JSON.stringify(frame))
    }
    options.onCancel?.()
  }

  attach()

  onBeforeUnmount(() => {
    stopped = true
    close()
  })

  return { connected, fallback, progress, events, terminal, cancel, close }
}

// ---------------------------------------------------------------------------
// 默认实现：浏览器原生 WebSocket + XSRF-TOKEN cookie 读取。
// ---------------------------------------------------------------------------

function defaultSocketFactory(url: string): SocketLike {
  // 直接 new WebSocket 即可：浏览器全局类型满足 SocketLike。
  const ws = new WebSocket(url)
  return ws as unknown as SocketLike
}

function defaultReadCsrf(): string | null {
  if (typeof document === 'undefined') {
    return null
  }
  const target = 'XSRF-TOKEN='
  for (const part of document.cookie.split(';')) {
    const p = part.trim()
    if (p.startsWith(target)) {
      return decodeURIComponent(p.substring(target.length))
    }
  }
  return null
}

// 显式 re-export 让测试断言 RUN_WS_SCHEMA_VERSION 一致；不会改变运行时行为。
export { RUN_WS_SCHEMA_VERSION }

// 占位类型 export，让单测可以直接 import type 同名（不改变运行时行为）。
export type { RunEventDto }
