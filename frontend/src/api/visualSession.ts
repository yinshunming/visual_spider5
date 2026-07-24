import { http } from '../http'
import type {
  PreviewResult,
  ValidateSelectorsRequest,
  ValidateSelectorsResponse,
  VisualSessionDto,
} from '../contracts/visualSession'

/**
 * M2-5 #21 REST typed client。
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
  preview(sessionId: string, definition: unknown): Promise<PreviewResult> {
    return http.post<PreviewResult>(
      `/api/visual-sessions/${encodeURIComponent(sessionId)}/preview`,
      { definition },
    )
  },
}