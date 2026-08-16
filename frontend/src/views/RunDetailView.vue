<script setup lang="ts">
/**
 * M3-6 #28 运行详情视图（spec §D18）。
 *
 * - 进入页面并行：REST 拉一次 RunDetail（兜底显示）+ 建 WS；
 * - WS PROGRESS / EVENT / TERMINAL 帧持续更新；
 * - WS 断开时 {@code useRunWebSocket.fallback=true}，本视图用 5s REST 轮询兜底
 *   （替代 {@code useRunPolling} 复用 list 的 fetcher —— 这里走 {@code runApi.get}）；
 * - 组件：ProgressPanel / SnapshotViewer / ResultsTable / EventStream / ExportButtons；
 * - 取消：WS 优先；本地按钮也调 {@link runApi.cancel}（服务端幂等）。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ProgressPanel from '../components/run/ProgressPanel.vue'
import RunCounters from '../components/run/RunCounters.vue'
import SnapshotViewer from '../components/run/SnapshotViewer.vue'
import ResultsTable from '../components/run/ResultsTable.vue'
import EventStream from '../components/run/EventStream.vue'
import ExportButtons from '../components/run/ExportButtons.vue'
import { runApi, downloadRunExport } from '../api/run'
import { useRunWebSocket } from '../composables/useRunWebSocket'
import type { RunDetail, RunEventsResponse, RunResultsResponse, RunSnapshotResponse } from '../contracts/run'
import { ApiError } from '../http'

const route = useRoute()
const router = useRouter()

const runId = computed<number>(() => {
  const id = Number(route.params.id)
  return Number.isFinite(id) ? id : 0
})

const detail = ref<RunDetail | null>(null)
const loadingDetail = ref(false)
const detailError = ref<string | null>(null)

const snapshot = ref<RunSnapshotResponse | null>(null)
const loadingSnapshot = ref(false)
const snapshotError = ref<string | null>(null)

async function loadDetail(): Promise<void> {
  if (runId.value <= 0) return
  loadingDetail.value = true
  detailError.value = null
  try {
    detail.value = await runApi.get(runId.value)
  } catch (e) {
    detailError.value =
      e instanceof ApiError ? `${e.status} ${e.message}` : e instanceof Error ? e.message : String(e)
  } finally {
    loadingDetail.value = false
  }
}

async function loadSnapshot(): Promise<void> {
  if (runId.value <= 0) return
  loadingSnapshot.value = true
  snapshotError.value = null
  try {
    snapshot.value = await runApi.snapshot(runId.value)
  } catch (e) {
    // 404 表示非 owner/admin -> RESOURCE_NOT_FOUND，引导回列表
    if (e instanceof ApiError && e.status === 404) {
      snapshotError.value = '快照不可访问'
      return
    }
    snapshotError.value =
      e instanceof ApiError ? `${e.status} ${e.message}` : e instanceof Error ? e.message : String(e)
  } finally {
    loadingSnapshot.value = false
  }
}

const ws = useRunWebSocket({
  runId: computed(() => runId.value).value,
  baseHref: typeof window === 'undefined' ? 'http://localhost' : window.location.origin,
})

let fallbackTimer: ReturnType<typeof setInterval> | null = null

function startFallbackPolling(): void {
  if (fallbackTimer !== null) return
  fallbackTimer = setInterval(() => {
    if (!ws.fallback.value) return
    void loadDetail()
  }, 5_000)
}

function stopFallbackPolling(): void {
  if (fallbackTimer !== null) {
    clearInterval(fallbackTimer)
    fallbackTimer = null
  }
}

watch(
  () => ws.fallback.value,
  (now) => {
    if (now) startFallbackPolling()
    else stopFallbackPolling()
  },
)

watch(
  () => runId.value,
  () => {
    void loadDetail()
    void loadSnapshot()
  },
  { immediate: true },
)

async function cancelRun(): Promise<void> {
  // WS 优先：useRunWebSocket.cancel 会发送 CANCEL 帧；服务端等价 POST cancel。
  // 同时显式发 POST 一次以兼容 fallback 模式（服务端幂等）。
  ws.cancel()
  try {
    await runApi.cancel(runId.value)
  } catch (e) {
    if (e instanceof ApiError) {
      // 终态返回 RUN_NOT_CANCELLABLE 时由 UI 状态体现
      detailError.value = `取消失败：${e.status} ${e.message}`
    }
  }
  await loadDetail()
}

function backToList(): void {
  void router.push({ name: 'runs' })
}

async function fetchResults(runId: number, page: number, size: number): Promise<RunResultsResponse> {
  return runApi.results(runId, { page, size })
}

async function fetchEvents(runId: number, page: number, size: number): Promise<RunEventsResponse> {
  return runApi.events(runId, { page, size })
}

async function onExport(format: 'csv' | 'json'): Promise<void> {
  await downloadRunExport(runId.value, format)
}

onBeforeUnmount(() => {
  stopFallbackPolling()
})
</script>

<template>
  <div class="run-detail-view">
    <header class="rd-head">
      <button class="btn btn--ghost" @click="backToList">← 返回列表</button>
      <h2>运行 #{{ runId }}</h2>
      <ExportButtons :run-id="runId" />
    </header>
    <div v-if="detailError" class="rd-error">错误：{{ detailError }}</div>
    <div v-if="loadingDetail && !detail" class="rd-loading">加载详情中…</div>
    <ProgressPanel
      :run-id="runId"
      :progress="ws.progress.value"
      :fallback-detail="detail"
      :connected="ws.connected.value"
      :fallback="ws.fallback.value"
      :cancel-disabled="ws.terminal.value !== null"
      @cancel="cancelRun"
    />
    <RunCounters
      v-if="detail?.mode === 'LIST'"
      :raw="detail.recordCountRaw"
      :dedup="detail.recordCountDedup"
      :final="detail.recordCountFinal"
      :fail="detail.failCount"
      mode="LIST"
    />
    <SnapshotViewer
      :snapshot="snapshot"
      :loading="loadingSnapshot"
      :error="snapshotError"
    />
    <ResultsTable :run-id="runId" :fetch-page="fetchResults" />
    <EventStream :run-id="runId" :fetch-page="fetchEvents" />
    <div class="rd-export">
      <button class="btn" @click="onExport('csv')" :disabled="ws.terminal.value === null">
        下载 CSV
      </button>
      <button class="btn" @click="onExport('json')" :disabled="ws.terminal.value === null">
        下载 JSON
      </button>
    </div>
  </div>
</template>

<style scoped>
.run-detail-view {
  max-width: 1200px;
  margin: 0 auto;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.rd-head {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}
.rd-head h2 {
  margin: 0;
  font-size: 1.4rem;
  flex: 1;
}
.rd-error,
.rd-loading {
  background: #fff3e0;
  border-left: 4px solid #fb8c00;
  padding: 0.5rem 0.75rem;
  border-radius: 4px;
  font-size: 0.9rem;
}
.rd-error { color: var(--color-error, #b91c1c); border-color: var(--color-error, #b91c1c); background: #ffebee; }
.rd-loading { color: var(--color-muted, #6b7280); }
.rd-export {
  display: flex;
  gap: 0.5rem;
}
.btn {
  background: transparent;
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
  padding: 0.3rem 0.7rem;
  cursor: pointer;
  font-size: 0.85rem;
}
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn--ghost { background: transparent; }
</style>
