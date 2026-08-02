/**
 * M3-6 #28 列表轮询 composable（spec §D18 "列表可见时每 5s GET /api/runs 轮询"）。
 *
 * 设计要点：
 *   - 立即拉一次，刷新无需等满 5s；
 *   - 同源周期内合并：未完成的请求不重复触发；
 *   - 错误隔离：单次失败不破坏下一次轮询，错误只在控制台记录，不抛给上层；
 *   - 卸载时停止定时器，并记住终止状态，以防迟到的 await 误写 ref。
 *   - 由 caller 注入 {@code fetcher}（默认用 {@link runApi.list}），避免在本文件耦合 HTTP/WS。
 */

import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import type { RunListResponse, RunState } from '../contracts/run'
import { runApi } from '../api/run'

export interface UseRunPollingOptions {
  /** 提供分页列表查询实现（默认 {@link runApi.list}），便于单测注入。 */
  fetcher?: (params: { status?: RunState }) => Promise<RunListResponse>
  /** 可选状态过滤；变化时立刻触发一次拉取。 */
  status?: Ref<RunState | undefined> | RunState | undefined
  /** 轮询周期（毫秒），默认 5000。spec §D18 = 5s。 */
  intervalMs?: number
  /** 分页大小。默认 20。 */
  size?: number
}

export interface UseRunPollingResult {
  items: Ref<RunListResponse['items']>
  total: Ref<number>
  loading: Ref<boolean>
  error: Ref<string | null>
  /** 主动拉一次；不等满周期。 */
  refresh: () => Promise<void>
  /** 卸载 / 离开页面时调用，提早停掉定时器。 */
  stop: () => void
}

export function useRunPolling(options: UseRunPollingOptions = {}): UseRunPollingResult {
  const fetcher = options.fetcher ?? defaultFetcher
  const intervalMs = options.intervalMs ?? 5_000
  const size = options.size ?? 20

  const items = ref<RunListResponse['items']>([])
  const total = ref<number>(0)
  const loading = ref<boolean>(false)
  const error = ref<string | null>(null)

  let stopped = false
  let inFlight: Promise<void> | null = null
  let timer: ReturnType<typeof setInterval> | null = null

  async function fetchOnce(): Promise<void> {
    if (stopped) {
      return
    }
    if (inFlight) {
      return inFlight
    }
    const statusValue =
      options.status === undefined
        ? undefined
        : isRef(options.status)
          ? (options.status as Ref<RunState | undefined>).value
          : options.status
    loading.value = true
    inFlight = (async () => {
      try {
        const res = await fetcher({
          ...(statusValue ? { status: statusValue } : {}),
          page: 1,
          size,
        } as { status?: RunState })
        if (stopped) {
          return
        }
        items.value = res.items
        total.value = res.total
        error.value = null
      } catch (e) {
        // 单次失败不破坏下一次轮询；只在 ref 写一条错误展示给 caller。
        if (stopped) {
          return
        }
        error.value = e instanceof Error ? e.message : String(e)
        // 控制台只打调试级，详细错误由视图层决定是否 toast。
        // eslint-disable-next-line no-console
        console.warn('[useRunPolling] fetch failed', e)
      } finally {
        loading.value = false
        inFlight = null
      }
    })()
    return inFlight
  }

  function start(): void {
    if (stopped || timer !== null) {
      return
    }
    timer = setInterval(() => {
      void fetchOnce()
    }, intervalMs)
  }

  function stop(): void {
    stopped = true
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  // 立刻拉一次，然后启动定时器。
  void fetchOnce()
  start()

  // status 变更立刻触发一次。
  if (isRef(options.status)) {
    watch(
      options.status as Ref<RunState | undefined>,
      () => {
        void fetchOnce()
      },
    )
  }

  onBeforeUnmount(stop)

  return {
    items,
    total,
    loading,
    error,
    refresh: fetchOnce,
    stop,
  }
}

async function defaultFetcher(params: { status?: RunState; page?: number; size?: number }): Promise<RunListResponse> {
  // runApi.list 接受 status/page/size 三个可选字段；这里直接转发。
  const opts: { status?: RunState; page?: number; size?: number } = {}
  if (params.status) opts.status = params.status
  if (params.page !== undefined) opts.page = params.page
  if (params.size !== undefined) opts.size = params.size
  return runApi.list(opts)
}

function isRef<T>(v: T | Ref<T>): v is Ref<T> {
  return (
    v !== null &&
    typeof v === 'object' &&
    'value' in (v as object)
  )
}
