/**
 * M2-5 #21 WebSocket 协议 schema（schemaVersion: 1）。
 *
 * 在消息发送 / 接收边界运行时校验。服务器拒绝任何 schemaVersion ≠ 1 的帧。
 */

export const WS_SCHEMA_VERSION = 1 as const;

export type InputMode = 'BROWSE' | 'SELECT'

export interface BaseFrame {
  schemaVersion: typeof WS_SCHEMA_VERSION
}

export interface InputFrame extends BaseFrame {
  type:
    | 'click'
    | 'wheel'
    | 'key'
    | 'navigate'
    | 'back'
    | 'forward'
    | 'reload'
    | 'select'
    | 'validate'
    | 'switchMode'
  sessionId: string
  sequence: number
  clientWidth: number
  clientHeight: number
  mode?: InputMode
  x?: number
  y?: number
  deltaX?: number
  deltaY?: number
  key?: string
  url?: string
  selector?: string
  selectorType?: 'css' | 'xpath'
}

export interface StatusFrame extends BaseFrame {
  type: 'status'
  sessionId: string
  url: string | null
  remoteWidth: number
  remoteHeight: number
  error: string | null
  selection?: {
    tagName: string
    id: string
    className: string
    text: string
    x: number
    y: number
    width: number
    height: number
    cssCandidates: string[]
    xpathCandidates: string[]
  }
}

export type ServerFrame = StatusFrame | { type: 'error'; schemaVersion: typeof WS_SCHEMA_VERSION; error: string }

export function isValidFrame(obj: unknown): obj is InputFrame | ServerFrame {
  if (!obj || typeof obj !== 'object') {
    return false
  }
  const candidate = obj as { schemaVersion?: unknown }
  return candidate.schemaVersion === WS_SCHEMA_VERSION
}

/**
 * 构造客户端入站帧：在发送边界注入 schemaVersion，发送后再校验接收帧。
 */
export function frame(type: InputFrame['type'], payload: Omit<InputFrame, 'schemaVersion' | 'type'>): InputFrame {
  return { schemaVersion: WS_SCHEMA_VERSION, type, ...payload }
}