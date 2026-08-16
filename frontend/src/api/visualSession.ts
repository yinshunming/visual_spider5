import { http } from '../http'
import type {
  InferRequest,
  InferResponse,
  ListPreviewResult,
  PreviewResult,
  TaskDefinition,
  ValidateSelectorsRequest,
  ValidateSelectorsResponse,
  VisualSessionDto,
} from '../contracts/visualSession'

/**
 * M2-5 #21 REST typed client（M4-6 #36 扩 infer + preview-list）。
 *
 * 所有路径走同源 /api；CSRF token 由 {@link http} 自动注入。
 */
export const visualSessionApi = {
  open(taskId: number): Promise<VisualSessionDto> {
    return http.post<VisualSessionDto>('/api/visual-sessions', { taskId })
  },
  get(sessionId: string): Promise<VisualSessionDto> {
    return http.get<VisualSessionDto>(`/api/visual-sessions/${encodeURIComponent(sessionId)}`)
  },
  heartbeat(sessionId: string): Promise<void> {
    return http.post<void>(`/api/visual-sessions/${encodeURIComponent(sessionId)}/heartbeat`)
  },
  close(sessionId: string): Promise<void> {
    return http.delete<void>(`/api/visual-sessions/${encodeURIComponent(sessionId)}`)
  },
  validateSelectors(
    sessionId: string,
    request: ValidateSelectorsRequest,
  ): Promise<ValidateSelectorsResponse> {
    return http.post<ValidateSelectorsResponse>(
      `/api/visual-sessions/${encodeURIComponent(sessionId)}/selectors/validate`,
      request,
    )
  },
  preview(sessionId: string, definition: TaskDefinition): Promise<PreviewResult> {
    return http.post<PreviewResult>(
      `/api/visual-sessions/${encodeURIComponent(sessionId)}/preview`,
      { definition },
    )
  },
  /**
   * M4-6 #36 列表模式受限预览（spec §D9）：返回最多 20 条 PreviewResult 聚合 + totalMatchCount。
   */
  previewList(sessionId: string, definition: TaskDefinition): Promise<ListPreviewResult> {
    return http.post<ListPreviewResult>(
      `/api/visual-sessions/${encodeURIComponent(sessionId)}/preview-list`,
      { definition },
    )
  },
  /**
   * M4-2 #32 / #36 候选列表项推断（spec §D3）：按视口坐标采集 DOM 摘要，
   * 返回推断选择器 + score + ancestorPath + components + alternatives。
   */
  infer(sessionId: string, request: InferRequest): Promise<InferResponse> {
    return http.post<InferResponse>(
      `/api/visual-sessions/${encodeURIComponent(sessionId)}/infer`,
      request,
    )
  },
  patchBuffer(sessionId: string, definition: TaskDefinition): Promise<void> {
    return http.put<void>(`/api/visual-sessions/${encodeURIComponent(sessionId)}`, { definition })
  },
}