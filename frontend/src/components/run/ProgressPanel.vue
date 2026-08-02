<script setup lang="ts">
/**
 * M3-6 #28 详情顶部进度面板（spec §D16 / §D18 "WS 进度面板"）。
 *
 * 字段对齐 server PROGRESS 帧：status / stopReason / stage / currentUrl /
 * pageCount / recordCountRaw / recordCountFinal / failCount / elapsedMs。
 *
 * 数据来源：{@link useRunWebSocket} 的 progress ref；REST fallback 由父 view 提供 rest。
 * 取消：协作式调用 {@code useRunWebSocket.cancel()} 同时调 {@code runApi.cancel}
 * （等价操作；WS 路径优先，避免重复触发）。
 */
import { computed } from 'vue'
import RunStatusBadge from './RunStatusBadge.vue'
import type { RunDetail, RunState, StopReason } from '../../contracts/run'
import type { ProgressFrame } from '../../contracts/runWsProtocol'

const props = defineProps<{
  runId: number
  /** WS PROGRESS 帧；null = 还没收到或 WS 已掉 fallback。 */
  progress: ProgressFrame | null
  /** REST fallback 详情；用于页面初始渲染与 WS 断开时持续显示。 */
  fallbackDetail: RunDetail | null
  /** WS 连接状态。 */
  connected: boolean
  /** fallback flag。 */
  fallback: boolean
  cancelDisabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'cancel'): void
}>()

const status = computed<RunState | null>(() => {
  if (props.progress?.status) return props.progress.status
  return props.fallbackDetail?.status ?? null
})

const stopReason = computed<StopReason | null>(() => {
  if (props.progress?.stopReason !== undefined) return props.progress.stopReason
  return props.fallbackDetail?.stopReason ?? null
})

const stage = computed<string | null>(() => {
  if (props.progress?.stage !== undefined) return props.progress.stage
  return props.fallbackDetail?.stage ?? null
})

const currentUrl = computed<string | null>(() => {
  if (props.progress?.currentUrl !== undefined) return props.progress.currentUrl
  return props.fallbackDetail?.currentUrl ?? null
})

const pageCount = computed<number>(() => {
  return props.progress?.pageCount ?? props.fallbackDetail?.pageCount ?? 0
})

const recordRaw = computed<number>(() => {
  return props.progress?.recordCountRaw ?? props.fallbackDetail?.recordCountRaw ?? 0
})

const recordFinal = computed<number>(() => {
  return props.progress?.recordCountFinal ?? props.fallbackDetail?.recordCountFinal ?? 0
})

const failCount = computed<number>(() => {
  return props.progress?.failCount ?? props.fallbackDetail?.failCount ?? 0
})

const elapsedMs = computed<number>(() => props.progress?.elapsedMs ?? 0)

const cancelled = computed<boolean>(() => {
  return props.fallbackDetail?.cancelRequested === true
})

function formatElapsed(ms: number): string {
  if (ms <= 0) return '—'
  const sec = Math.floor(ms / 1000)
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return m > 0 ? `${m}分${s}秒` : `${s}秒`
}

const isTerminal = computed<boolean>(() => {
  if (!status.value) return false
  return (
    status.value === 'SUCCESS' ||
    status.value === 'PARTIAL_SUCCESS' ||
    status.value === 'FAILED' ||
    status.value === 'CANCELLED' ||
    status.value === 'INTERRUPTED'
  )
})
</script>

<template>
  <section class="progress" aria-label="运行进度">
    <header class="progress__head">
      <h3>运行 #{{ runId }}</h3>
      <RunStatusBadge v-if="status" :status="status" />
      <span v-if="stopReason" class="progress__stop">stopReason: {{ stopReason }}</span>
      <span class="progress__conn" :data-fallback="fallback">
        <span v-if="connected">WS</span>
        <span v-else-if="fallback">REST fallback</span>
        <span v-else>连接中…</span>
      </span>
    </header>
    <dl class="progress__grid">
      <div>
        <dt>阶段</dt>
        <dd>{{ stage ?? '—' }}</dd>
      </div>
      <div>
        <dt>当前 URL</dt>
        <dd class="progress__url">{{ currentUrl ?? '—' }}</dd>
      </div>
      <div>
        <dt>页 / 记录（final）/ 失败</dt>
        <dd>{{ pageCount }} / {{ recordFinal }} / {{ failCount }}</dd>
      </div>
      <div>
        <dt>原始记录</dt>
        <dd>{{ recordRaw }}</dd>
      </div>
      <div>
        <dt>耗时</dt>
        <dd>{{ formatElapsed(elapsedMs) }}</dd>
      </div>
      <div>
        <dt>取消请求</dt>
        <dd>{{ cancelled ? '已请求' : '未请求' }}</dd>
      </div>
    </dl>
    <div class="progress__actions">
      <button
        class="btn btn--danger"
        :disabled="props.cancelDisabled || isTerminal"
        @click="emit('cancel')"
      >
        取消运行
      </button>
    </div>
  </section>
</template>

<style scoped>
.progress {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 6px;
  padding: 1rem 1.25rem;
}
.progress__head {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-bottom: 0.75rem;
}
.progress__head h3 {
  margin: 0;
  font-size: 1.05rem;
}
.progress__stop {
  font-size: 0.75rem;
  color: var(--color-muted, #6b7280);
}
.progress__conn {
  font-size: 0.75rem;
  color: var(--color-muted, #6b7280);
  margin-left: auto;
}
.progress__conn[data-fallback='true'] { color: #b45309; }
.progress__grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 0.5rem 1.5rem;
  margin: 0 0 1rem;
}
.progress__grid dt {
  font-size: 0.7rem;
  text-transform: uppercase;
  color: var(--color-muted, #6b7280);
}
.progress__grid dd {
  margin: 0;
  font-size: 0.9rem;
  word-break: break-all;
}
.progress__url {
  font-family: ui-monospace, SFMono-Regular, monospace;
  font-size: 0.8rem;
}
.btn {
  background: var(--color-accent, #2563eb);
  color: #fff;
  border: none;
  border-radius: 4px;
  padding: 0.45rem 1rem;
  font-size: 0.9rem;
  cursor: pointer;
}
.btn--danger { background: #b00020; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
