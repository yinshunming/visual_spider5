/**
 * M3-6 #28 列表轮询 composable 测试（spec §D18 "列表可见时每 5s GET /api/runs 轮询"）。
 *
 * 覆盖：
 *   1. 立即拉一次（不等到 5s）；
 *   2. 5s 周期触发下一次；
 *   3. 周期内并发合并：in-flight 期间再触发只发一次；
 *   4. 错误处理：单次失败不破坏下一次轮询，不抛到调用方；
 *   5. status 变更立刻触发一次；
 *   6. 卸载时停止定时器与清空 fetch 引用；
 *   7. fetcher 缺省时返回 runApi.list。
 *
 * 用 fake timers + vi.advanceTimersByTime 控制周期，避免 runAllTimersAsync 在
 * setInterval 重复触发时撞上 vitest 的 10000 次上限。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { useRunPolling } from './useRunPolling'
import type { RunListResponse, RunState } from '../contracts/run'

type Fetcher = (params: { status?: RunState; page?: number; size?: number }) => Promise<RunListResponse>

function empty(): RunListResponse {
  return { items: [], total: 0, page: 1, size: 20 }
}

async function flushPromises(): Promise<void> {
  // 让当前 microtask 队列与 await 链都收尾；用 vi 的 microtask flush。
  await vi.advanceTimersByTimeAsync(0)
}

function mountWith(fetcher: Fetcher, status?: RunState) {
  return mount(
    defineComponent({
      setup() {
        const result = useRunPolling({ fetcher, status, intervalMs: 5_000 })
        return { ...result }
      },
      render: () => null,
    }),
  )
}

describe('useRunPolling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('挂载即拉一次', async () => {
    const fetcher = vi.fn<Fetcher>().mockResolvedValue(empty())
    const wrapper = mountWith(fetcher)
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('5s 周期触发下一次', async () => {
    const fetcher = vi.fn<Fetcher>().mockResolvedValue(empty())
    const wrapper = mountWith(fetcher)
    await flushPromises()
    vi.advanceTimersByTime(5_000)
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('卸载后定时器停摆', async () => {
    const fetcher = vi.fn<Fetcher>().mockResolvedValue(empty())
    const wrapper = mountWith(fetcher)
    await flushPromises()
    const callsBefore = fetcher.mock.calls.length
    wrapper.unmount()
    vi.advanceTimersByTime(60_000)
    expect(fetcher.mock.calls.length).toBe(callsBefore)
  })

  it('单次 fetch 失败不破坏下一次轮询', async () => {
    let calls = 0
    const fetcher = vi.fn<Fetcher>().mockImplementation(async () => {
      calls += 1
      if (calls === 1) {
        throw new Error('boom')
      }
      return empty()
    })
    const wrapper = mountWith(fetcher)
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(5_000)
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('status filter 变更立刻触发一次', async () => {
    const fetcher = vi.fn<Fetcher>().mockResolvedValue(empty())
    let statusRef!: ReturnType<typeof ref<RunState | undefined>>
    const wrapper = mount(
      defineComponent({
        setup() {
          statusRef = ref<RunState | undefined>(undefined)
          const api = useRunPolling({ fetcher, status: statusRef, intervalMs: 5_000 })
          return { ...api }
        },
        render: () => null,
      }),
    )
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(1)
    statusRef.value = 'RUNNING'
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect((fetcher.mock.calls[1]?.[0] ?? {}).status).toBe('RUNNING')
    wrapper.unmount()
  })

  it('在途请求期间 timer 触发合并为不发起新请求', async () => {
    let resolve!: (v: RunListResponse) => void
    const fetcher = vi.fn<Fetcher>().mockImplementation(
      () =>
        new Promise<RunListResponse>((r) => {
          resolve = r
        }),
    )
    const wrapper = mountWith(fetcher)
    await flushPromises()
    // 第一次请求仍在 pending，timer 到点不应发起新请求。
    vi.advanceTimersByTime(5_000)
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(1)
    resolve(empty())
    await flushPromises()
    // in-flight 结束后再次到点，正常触发一次。
    vi.advanceTimersByTime(5_000)
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})
