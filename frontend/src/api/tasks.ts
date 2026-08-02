import { http } from '../http'
import type { TaskSummary } from '../contracts/task'

/**
 * M3-6 #28 任务列表入口：仅 {@code GET /api/tasks} 列出当前用户的任务，
 * 用于在任务卡片上加「启动运行」按钮（仅 {@code status === 'READY'} 可点）。
 *
 * 不消费任务编辑契约：M3-6 不修改任务，只读取只读摘要并触发 {@code POST /api/runs}。
 * CSRF token 由 {@link http} 自动注入。
 */
export const tasksApi = {
  list(): Promise<TaskSummary[]> {
    return http.get<TaskSummary[]>('/api/tasks')
  },
}
