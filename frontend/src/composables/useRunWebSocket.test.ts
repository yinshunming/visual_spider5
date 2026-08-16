/**
 * M3-6 #28 详情 WS composable 测试（spec §D18 "WS 断开回退 REST 轮询"）。
 *
 * 覆盖：
 *   1. 挂载即建立 WS（路径 + CSRF query）；
 *   2. PROGRESS/EVENT/TERMINAL 分别更新对应 ref；
 *   3. TERMINAL 到达后自动关闭 WS；
 *   4. WS 异常关闭 -> fallback=true 让上层走 REST 轮询；
 *   5. cancel() 发送合法 CANCEL 帧；
 *   6. schemaVersion 校验失败的消息被忽略；
 *   7. 卸载时主动 close，不挂回调。
 *
 * 用 FakeSocket 替换 {@code new WebSocket(url)}，避免依赖 jsdom 的 ws shim。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { useRunWebSocket, type SocketLike } from './useRunWebSocket'
import type { RunDetail } from '../contracts/run'
import {
  RUN_WS_SCHEMA_VERSION,
  type ProgressFrame,
  type TerminalFrame,
  type EventFrame,
  type CancelFrame,
} from '../contracts/runWsProtocol'

class FakeSocket implements SocketLike {
  static OPEN = 1
  static CLOSED = 3
  readyState = 0
  onopen: (() => void) | null = null
  onmessage: ((payload: string) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  sent: string[] = []

  constructor(public url: string) {}

  send(payload: string): void {
    this.sent.push(payload)
  }

  close(): void {
    if (this.readyState === FakeSocket.CLOSED) return
    this.readyState = FakeSocket.CLOSED
    this.onclose?.()
  }

  open(): void {
    this.readyState = FakeSocket.OPEN
    this.onopen?.()
  }

  receive(frame: unknown): void {
    this.onmessage?.(JSON.stringify(frame))
  }

  fail(): void {
    this.readyState = FakeSocket.CLOSED
    this.onerror?.()
    this.onclose?.()
  }
}

function makeDetail(): RunDetail {
  return {
    runId: 42,
    taskId: 7,
    ownerId: 1,
    status: 'WAITING',
    stopReason: null,
    cancelRequested: false,
    pageCount: 0,
    recordCountFinal: 0,
    failCount: 0,
    createdAt: '2026-08-02T00:00:00Z',
    startedAt: null,
    finishedAt: null,
    recordCountRaw: 0,
    recordCountDedup: 0,
    currentUrl: null,
    stage: null,
    snapshotMeta: {
      name: 'demo',
      mode: 'SINGLE_PAGE',
      schemaVersion: 1,
      taskVersion: 1,
      definition: {
        schemaVersion: 2,
        mode: 'SINGLE_PAGE',
        startUrl: 'http://example.com',
        viewport: { width: 1280, height: 720 },
        fields: [],
      },
    },
  }
}

interface ApiBag {
  api: ReturnType<typeof useRunWebSocket>
  sockets: FakeSocket[]
}

function mountWithFakeSockets(): { wrapper: ReturnType<typeof mount>; bag: ApiBag } {
  const sockets: FakeSocket[] = []
  let captured!: ReturnType<typeof useRunWebSocket>
  const wrapper = mount(
    defineComponent({
      setup() {
        captured = useRunWebSocket({
          runId: 42,
          baseHref: 'http://localhost',
          socketFactory: (url: string) => {
            const s = new FakeSocket(url)
            sockets.push(s)
            return s
          },
          fallbackRest: async () => makeDetail(),
        })
        return { ...captured, render: () => null }
      },
      render: () => h('div'),
    }),
  )
  const bag: ApiBag = {
    get api() {
      return captured
    },
    sockets,
  }
  return { wrapper, bag }
}

describe('useRunWebSocket', () => {
  beforeEach(() => {
    // 真实测试不需 fake timers；但 vi 提供默认。
  })

  afterEach(() => {
    // noop
  })

  it('挂载即建立 WS（路径 + CSRF query）', async () => {
    const { wrapper, bag } = mountWithFakeSockets()
    await nextTick()
    expect(bag.sockets.length).toBe(1)
    expect(bag.sockets[0]!.url).toContain('/ws/runs/42')
    expect(bag.sockets[0]!.url).toContain('csrf=')
    bag.sockets[0]!.open()
    await nextTick()
    expect(bag.api.connected.value).toBe(true)
    expect(bag.api.fallback.value).toBe(false)
    wrapper.unmount()
  })

  it('PROGRESS 帧更新 progress ref', async () => {
    const { wrapper, bag } = mountWithFakeSockets()
    await nextTick()
    bag.sockets[0]!.open()
    await nextTick()
    const progress: ProgressFrame = {
      schemaVersion: RUN_WS_SCHEMA_VERSION,
      type: 'PROGRESS',
      status: 'RUNNING',
      stopReason: null,
      stage: 'NAVIGATE',
      currentUrl: 'http://example.com',
      pageCount: 1,
      recordCountRaw: 0,
      recordCountFinal: 0,
      failCount: 0,
      elapsedMs: 123,
    }
    bag.sockets[0]!.receive(progress)
    await nextTick()
    expect(bag.api.progress.value?.status).toBe('RUNNING')
    expect(bag.api.progress.value?.stage).toBe('NAVIGATE')
    wrapper.unmount()
  })

  it('EVENT 帧追加到 events ref', async () => {
    const { wrapper, bag } = mountWithFakeSockets()
    await nextTick()
    bag.sockets[0]!.open()
    await nextTick()
    const event: EventFrame = {
      schemaVersion: RUN_WS_SCHEMA_VERSION,
      type: 'EVENT',
      id: 1,
      level: 'INFO',
      stage: 'NAVIGATE',
      url: null,
      errorCode: null,
      message: 'opened',
      createdAt: 1,
    }
    bag.sockets[0]!.receive(event)
    await nextTick()
    expect(bag.api.events.value.length).toBe(1)
    expect(bag.api.events.value[0]?.message).toBe('opened')
    wrapper.unmount()
  })

  it('TERMINAL 帧到达后自动关闭 WS', async () => {
    const { wrapper, bag } = mountWithFakeSockets()
    await nextTick()
    bag.sockets[0]!.open()
    await nextTick()
    const terminal: TerminalFrame = {
      schemaVersion: RUN_WS_SCHEMA_VERSION,
      type: 'TERMINAL',
      status: 'SUCCESS',
      stopReason: 'COMPLETED',
      finishedAt: 1,
    }
    bag.sockets[0]!.receive(terminal)
    await nextTick()
    expect(bag.api.terminal.value?.status).toBe('SUCCESS')
    expect(bag.sockets[0]!.readyState).toBe(FakeSocket.CLOSED)
    wrapper.unmount()
  })

  it('WS 异常关闭 -> fallback=true', async () => {
    const { wrapper, bag } = mountWithFakeSockets()
    await nextTick()
    bag.sockets[0]!.open()
    await nextTick()
    expect(bag.api.fallback.value).toBe(false)
    bag.sockets[0]!.fail()
    await nextTick()
    expect(bag.api.fallback.value).toBe(true)
    wrapper.unmount()
  })

  it('cancel() 发送合法 CANCEL 帧', async () => {
    const { wrapper, bag } = mountWithFakeSockets()
    await nextTick()
    bag.sockets[0]!.open()
    await nextTick()
    bag.api.cancel()
    expect(bag.sockets[0]!.sent.length).toBe(1)
    const sent = JSON.parse(bag.sockets[0]!.sent[0]!) as CancelFrame
    expect(sent.schemaVersion).toBe(RUN_WS_SCHEMA_VERSION)
    expect(sent.type).toBe('CANCEL')
    wrapper.unmount()
  })

  it('schemaVersion 错误的消息被忽略', async () => {
    const { wrapper, bag } = mountWithFakeSockets()
    await nextTick()
    bag.sockets[0]!.open()
    await nextTick()
    bag.sockets[0]!.receive({ schemaVersion: 99, type: 'PROGRESS' })
    await nextTick()
    expect(bag.api.progress.value).toBeNull()
    wrapper.unmount()
  })

  it('卸载时关闭 WS', async () => {
    const { wrapper, bag } = mountWithFakeSockets()
    await nextTick()
    bag.sockets[0]!.open()
    await nextTick()
    wrapper.unmount()
    expect(bag.sockets[0]!.readyState).toBe(FakeSocket.CLOSED)
  })
})
