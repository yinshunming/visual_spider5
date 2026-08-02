/**
 * M2-5 #21 REST 契约（schemaVersion: 1）。
 *
 * 由后端 OpenAPI/手写源类型生成；前端不得手工修改字段名。
 */

export const VISUAL_SESSION_SCHEMA_VERSION = 1 as const;

export type FieldSource = 'VISIBLE_TEXT' | 'ATTRIBUTE' | 'LINK_URL' | 'IMAGE_URL' | 'PAGE_URL'
export type ResultType = 'TEXT' | 'NUMBER' | 'URL'
export type TrimPolicy = 'TRIM' | 'PRESERVE'
export type TaskStatus = 'DRAFT' | 'READY'
export type TaskMode = 'SINGLE_PAGE' | 'LIST'
export type LifecycleState = 'ACTIVE' | 'IDLE_CLOSING' | 'MAX_REACHED_CLOSING' | 'USER_CLOSING' | 'CLOSED'

/**
 * M3 新增：字段选择器类型（spec §D6 / §D7）。可空 → 服务端默认 CSS。
 * 前端编辑器应保持 selectorType 与 selector 同步（CSS 选择器用 CSS；XPath 用 XPATH）。
 */
export type SelectorType = 'CSS' | 'XPATH'

/**
 * M3 新增：任务等待策略（spec §D6）。
 * {@code extraWaitSeconds} 必为 0-5；前端校验与服务端校验双重把关。
 */
export interface WaitPolicy {
  extraWaitSeconds: number  // 0-5
}

export interface FieldDefinition {
  name: string
  source: FieldSource
  selector?: string
  /** M3 新增：可空；服务端默认 CSS。 */
  selectorType?: SelectorType
  attributeName?: string
  resultType: ResultType
  trim: TrimPolicy
  regex?: string
  required: boolean
}

export interface TaskDefinition {
  schemaVersion: typeof VISUAL_SESSION_SCHEMA_VERSION
  mode: TaskMode
  startUrl: string
  viewport: { width: number; height: number }
  /** M3 新增：可空；服务端默认 WaitPolicy(0)。 */
  waitPolicy?: WaitPolicy
  fields: FieldDefinition[]
}

export interface VisualSessionDto {
  sessionId: string
  taskId: number
  openedAt: string
  lastActivityAt: string
  lifecycle: LifecycleState
}

export interface ValidateSelectorsRequest {
  selectors: Array<{ type: 'css' | 'xpath'; selector: string }>
}

export interface SelectorOutcome {
  selector: string
  type: string
  valid: boolean
  matchCount: number
  error: string | null
  matchedRanges: Array<{
    tagName: string
    id: string
    className: string
    text: string
    x: number
    y: number
    width: number
    height: number
  }>
}

export interface ValidateSelectorsResponse {
  outcomes: SelectorOutcome[]
}

export interface PreviewResult {
  fieldOutcomes: Array<{
    fieldName: string
    rawValue: string | null
    cleanedValue: string | null
    isEmpty: boolean
  }>
  diagnostics: Array<{
    code:
      | 'SELECTOR_SYNTAX_INVALID'
      | 'ZERO_MATCH'
      | 'MULTIPLE_MATCH'
      | 'REGEX_NO_MATCH'
      | 'TYPE_CONVERSION_FAILED'
      | 'ATTRIBUTE_MISSING'
      | 'FIELD_EMPTY'
    fieldName?: string
    userMessage: string
  }>
}

export interface BusinessError {
  code: string
  message: string
  fieldPath?: string
}