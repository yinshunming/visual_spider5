import { http } from '../http'
import type {
  ExportFormat,
  ResultRecord,
  RunDetail,
  RunEventDto,
  RunEventsResponse,
  RunListResponse,
  RunResultsResponse,
  RunSnapshotResponse,
  RunStartRequest,
  RunStartResponse,
  RunState,
} from '../contracts/run'

/**
 * M3-5 #27 REST typed client。
 *
 * 路径走同源 {@code /api/runs/**}；CSRF token 由 {@link http} 自动注入。
 * 导出端点（{@link exportResults}）返回 {@code Response}，由 caller 触发下载；
 * 其余路径返回反序列化后的 JSON。
 */
export const runApi = {
  /** POST /api/runs —— 启动运行。 */
  start(request: RunStartRequest): Promise<RunStartResponse> {
    return http.post<RunStartResponse>('/api/runs', request)
  },

  /** GET /api/runs —— 分页列表（可选 status filter）。 */
  list(params: { status?: RunState; page?: number; size?: number } = {}): Promise<RunListResponse> {
    const search = new URLSearchParams()
    if (params.status) {
      search.set('status', params.status)
    }
    if (params.page !== undefined) {
      search.set('page', String(params.page))
    }
    if (params.size !== undefined) {
      search.set('size', String(params.size))
    }
    const qs = search.toString()
    return http.get<RunListResponse>(qs ? `/api/runs?${qs}` : '/api/runs')
  },

  /** GET /api/runs/{runId} —— 详情。 */
  get(runId: number): Promise<RunDetail> {
    return http.get<RunDetail>(`/api/runs/${runId}`)
  },

  /** POST /api/runs/{runId}/cancel —— 协作式取消。 */
  cancel(runId: number): Promise<void> {
    return http.post<void>(`/api/runs/${runId}/cancel`)
  },

  /** GET /api/runs/{runId}/results —— 分页结果。 */
  results(runId: number, params: { page?: number; size?: number } = {}): Promise<RunResultsResponse> {
    const search = new URLSearchParams()
    if (params.page !== undefined) {
      search.set('page', String(params.page))
    }
    if (params.size !== undefined) {
      search.set('size', String(params.size))
    }
    const qs = search.toString()
    return http.get<RunResultsResponse>(qs ? `/api/runs/${runId}/results?${qs}` : `/api/runs/${runId}/results`)
  },

  /**
   * GET /api/runs/{runId}/results/export —— 流式导出。
   *
   * 返回原始 fetch Response，便于 caller 触发浏览器下载；
   * 不读 body，由浏览器自动保存。CSRF token 需显式拼接 query：
   * WS 端点的 csrfToken 走 query；REST 端点走 {@code X-XSRF-TOKEN} header（参考 {@link http}）。
   * 对于"GET + query"路径这里统一复用 {@code http.get} 风格时不可取（需流式读 Response），
   * 故采用直 fetch + 显式 CSRF header。
   */
  exportResults(runId: number, format: ExportFormat): Promise<Response> {
    return fetch(
      `/api/runs/${runId}/results/export?format=${encodeURIComponent(format)}`,
      {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: format === 'csv' ? 'text/csv' : 'application/json' },
      },
    )
  },

  /** GET /api/runs/{runId}/snapshot —— 固化任务定义。 */
  snapshot(runId: number): Promise<RunSnapshotResponse> {
    return http.get<RunSnapshotResponse>(`/api/runs/${runId}/snapshot`)
  },

  /** GET /api/runs/{runId}/events —— 分页事件。 */
  events(runId: number, params: { page?: number; size?: number } = {}): Promise<RunEventsResponse> {
    const search = new URLSearchParams()
    if (params.page !== undefined) {
      search.set('page', String(params.page))
    }
    if (params.size !== undefined) {
      search.set('size', String(params.size))
    }
    const qs = search.toString()
    return http.get<RunEventsResponse>(qs ? `/api/runs/${runId}/events?${qs}` : `/api/runs/${runId}/events`)
  },
}

/** 浏览器下载触发器：配合 {@link runApi.exportResults} 使用。 */
export async function downloadRunExport(
  runId: number,
  format: ExportFormat,
): Promise<void> {
  const res = await runApi.exportResults(runId, format)
  if (!res.ok) {
    throw new Error(`export failed: HTTP ${res.status}`)
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `run-${runId}.${format}`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

/** Re-export 常用类型，便于 caller 一处导入。 */
export type { ResultRecord, RunEventDto }
