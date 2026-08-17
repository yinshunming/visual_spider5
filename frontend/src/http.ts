/**
 * HTTP client：fetch 封装，自动从 cookie 读 XSRF-TOKEN 写入 X-XSRF-TOKEN header。
 *
 * <p>避免引入 axios（spec §14 M1-4 acceptance：前端 TypeScript 类型手工定义），
 * 用 fetch + cookie API 即可。CSRF token 由 Spring Security CookieCsrfTokenRepository 写入。
 */

const COOKIE_NAME = 'XSRF-TOKEN'

function readCookie(name: string): string | null {
  const target = `${name}=`
  const parts = document.cookie ? document.cookie.split(';') : []
  for (const raw of parts) {
    const c = raw.trim()
    if (c.startsWith(target)) {
      return decodeURIComponent(c.substring(target.length))
    }
  }
  return null
}

export interface ApiErrorPayload {
  code: string
  message: string
  fieldPath?: string | null
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly payload: ApiErrorPayload | null,
  ) {
    super(payload?.message ?? `HTTP ${status}`)
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  // CSRF：写入非 GET/HEAD 的方法
  if (method !== 'GET' && method !== 'HEAD') {
    const token = readCookie(COOKIE_NAME)
    if (token) {
      headers['X-XSRF-TOKEN'] = token
    }
  }
  const init: RequestInit = {
    method,
    headers,
    credentials: 'same-origin',
  }
  if (body !== undefined) {
    init.body = JSON.stringify(body)
  }
  const res = await fetch(path, init)
  if (!res.ok) {
    let payload: ApiErrorPayload | null = null
    try {
      payload = (await res.json()) as ApiErrorPayload
    } catch {
      // 非 JSON 错误体；忽略
    }
    throw new ApiError(res.status, payload)
  }
  if (res.status === 204) {
    return undefined as unknown as T
  }
  return (await res.json()) as T
}

export const http = {
  get<T>(path: string): Promise<T> {
    return request<T>('GET', path)
  },
  post<T>(path: string, body?: unknown): Promise<T> {
    return request<T>('POST', path, body)
  },
  put<T>(path: string, body?: unknown): Promise<T> {
    return request<T>('PUT', path, body)
  },
  delete<T>(path: string): Promise<T> {
    return request<T>('DELETE', path)
  },
}
