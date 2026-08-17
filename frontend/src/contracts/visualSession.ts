/**
 * M2-5 #21 REST 契约（schemaVersion: 2 / M4-1 #31 / M4-6 #36）。
 *
 * 由后端 OpenAPI/手写源类型生成；前端不得手工修改字段名。
 *
 * <p>V2 扩展：{@link TaskDefinition} 加 {@link listItemRule} / {@link uniqueKey} /
 * {@link limits}；新增 infer 与 preview-list 端点契约。V1 旧 reader 仍可读（依赖
 * 服务端 {@code @JsonIgnoreProperties(ignoreUnknown = true)}）。
 */

export const VISUAL_SESSION_SCHEMA_VERSION = 2 as const

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

/**
 * M4 新增：列表项规则（spec §D1 / §D3）。LIST 模式必填；SINGLE_PAGE 不感知。
 * {@code selectorType} 可空 → 服务端默认 CSS。
 */
export interface ListItemRule {
  selector: string
  selectorType?: SelectorType
}

/**
 * M4 新增：唯一键字段（spec §D1 / §D5）。
 * 采集人员选择若干字段作为运行内去重键；缺值或不存在 → 该 record 不参与去重。
 */
export interface UniqueKeyField {
  fieldName: string
}

/**
 * M4 新增：任务级运行限制（spec §D1）。
 * 不填走 {@code RunLimits} 全局默认（pageLimit ≤ 200 / recordLimit ≤ 10_000 /
 * durationLimit ≤ 30min）。
 */
export interface Limits {
  pageLimit: number
  recordLimit: number
  /** ISO-8601 持续时间字符串（如 {@code PT30M}）；前端编辑用秒数 / 分钟输入，由 UI 转 Duration。 */
  durationLimit: string
}

export interface TaskDefinition {
  schemaVersion: typeof VISUAL_SESSION_SCHEMA_VERSION
  mode: TaskMode
  startUrl: string
  viewport: { width: number; height: number }
  /** M3 新增：可空；服务端默认 WaitPolicy(0)。 */
  waitPolicy?: WaitPolicy
  /** M4 新增：可空；服务端默认 {@code Limits.globalDefault()}。 */
  limits?: Limits
  /** M4 新增：LIST 模式必填；SINGLE_PAGE 不使用。 */
  listItemRule?: ListItemRule
  /** M4 新增：可空；空数组 = 不去重。 */
  uniqueKey?: UniqueKeyField[]
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

/**
 * M4 新增：列表模式受限预览聚合（spec §D9 / M4-6 #36 薄包装）。
 * 服务端 {@code com.visualspider.visualbrowser.api.ListPreviewResponse} 形状：
 * 字段名与 SPI record 解耦，避免 SPI 演进影响前端契约。
 *
 * <p>{@code totalMatchCount} = {@code listItemRule} 命中总数；{@code previews.length}
 * = 实际预览条数（≤ 20 且 ≤ totalMatchCount）。
 */
export interface ListPreviewFieldOutcome {
  fieldName: string
  rawValue: string | null
  cleanedValue: string | null
  isEmpty: boolean
}

export interface ListPreviewDiagnostic {
  code: string
  fieldName?: string
  userMessage: string
}

export interface ListPreviewItem {
  fieldOutcomes: ListPreviewFieldOutcome[]
  diagnostics: ListPreviewDiagnostic[]
}

export interface ListPreviewResult {
  previews: ListPreviewItem[]
  totalMatchCount: number
  diagnostics: ListPreviewDiagnostic[]
}

/**
 * M4 新增：候选列表项推断请求（spec §D3）。坐标为客户端 CSS 像素；
 * 服务端经 ViewportMapper 换算到远程坐标。越界（含 0 / null）由 mapper 返 null
 * → 服务端抛 IllegalArgumentException。
 */
export interface InferRequest {
  x: number
  y: number
  clientWidth: number
  clientHeight: number
}

/**
 * M4 新增：候选列表项推断响应（spec §D3）。前端用 {@code score} + {@code components}
 * 渲染评分维度调试条；用 {@code ancestorPath} 渲染上溯路径；用 {@code alternatives}
 * 渲染并列候选。{@code lowConfidence=true} 表示无 ≥ 阈值候选（页面无重复结构）。
 */
export interface InferResponse {
  selector: string
  selectorType: string
  matchCount: number
  score: number
  ancestorPath: AncestorHopDto[]
  components: ScoreComponentDto[]
  alternatives: string[]
  lowConfidence: boolean
}

export interface AncestorHopDto {
  depth: number
  tagAndClass: string
}

export interface ScoreComponentDto {
  name: string
  raw: number
  weighted: number
  note: string | null
}

export interface BusinessError {
  code: string
  message: string
  fieldPath?: string
}

/**
 * M4 新增：任务 READY 校验错误（spec §D10）。前端 list-mode 配置面板按
 * {@code fieldPath} 在对应字段上回显红框 + tooltip。
 */
export interface ReadinessError {
  code:
    | 'TASK_INVALID_DEFINITION'
    | 'TASK_SCHEMA_OUTDATED'
    | 'TASK_UNSUPPORTED_SCHEMA'
    | 'TASK_INVALID_URL'
    | 'TASK_INVALID_VIEWPORT'
    | 'TASK_INVALID_WAIT_POLICY'
    | 'TASK_NO_FIELDS'
    | 'TASK_INVALID_FIELD_NAME'
    | 'TASK_DUPLICATE_FIELD'
    | 'TASK_INVALID_SELECTOR'
    | 'TASK_MISSING_ATTRIBUTE_NAME'
    | 'LIST_ITEM_RULE_MISSING'
    | 'LIST_ITEM_RULE_NO_MATCH'
    | 'MULTIPLE_MATCH'
    | 'UNIQUE_KEY_UNKNOWN_FIELD'
    | 'LIMITS_OUT_OF_RANGE'
  message: string
  fieldPath?: string
}