<script setup lang="ts">
/**
 * M3-6 #28 运行列表视图（spec §D18）。
 *
 * - 进入页面 5s 轮询 {@code GET /api/runs}（由 {@link useRunPolling} 承担）；
 * - 状态 filter 改变立刻拉一次；
 * - 表格行内"取消"按钮（仅 WAITING/RUNNING 可点）；
 * - 行内 CSV/JSON 导出下拉（终态可下，过程中导出空也允许）；
 * - 点击 runId 进入 {@code /runs/:id}。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import RunListTable from '../components/run/RunListTable.vue'
import { runApi } from '../api/run'
import { useRunPolling } from '../composables/useRunPolling'
import type { RunState } from '../contracts/run'

const router = useRouter()

const status = ref<RunState | ''>('')
const polling = useRunPolling({
  fetcher: (params) => runApi.list({
    ...(params.status ? { status: params.status } : {}),
    page: 1,
    size: 20,
  }),
  status: computed(() => (status.value || undefined) as RunState | undefined),
  intervalMs: 5_000,
  size: 20,
})

function clearFilter(): void {
  status.value = ''
}

function cancelRun(runId: number): void {
  void runApi.cancel(runId).catch((e) => {
    polling.error.value = e instanceof Error ? e.message : String(e)
  })
}

function openRun(runId: number): void {
  void router.push({ name: 'run-detail', params: { id: String(runId) } })
}

const STATUS_OPTIONS: ReadonlyArray<{ value: RunState; label: string }> = [
  { value: 'WAITING', label: '等待' },
  { value: 'RUNNING', label: '运行中' },
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'INTERRUPTED', label: '已中断' },
]
</script>

<template>
  <div class="run-list-view">
    <header class="rl-header">
      <h2>运行</h2>
      <div class="rl-header__filter">
        <label>
          状态：
          <select v-model="status">
            <option value="">全部</option>
            <option v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }} ({{ opt.value }})
            </option>
          </select>
        </label>
        <button v-if="status" class="btn btn--ghost" @click="clearFilter">清除</button>
        <span v-if="polling.error.value" class="rl-header__error">错误：{{ polling.error.value }}</span>
        <span class="rl-header__hint">每 5s 自动刷新</span>
      </div>
    </header>
    <RunListTable
      :items="polling.items.value"
      :loading="polling.loading.value"
      @cancel="cancelRun"
      @open="openRun"
    />
  </div>
</template>

<style scoped>
.run-list-view {
  max-width: 1200px;
  margin: 0 auto;
  padding: 1.5rem;
}
.rl-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 1rem;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.rl-header h2 {
  margin: 0;
  font-size: 1.4rem;
}
.rl-header__filter {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
}
.rl-header__hint {
  font-size: 0.75rem;
  color: var(--color-muted, #6b7280);
}
.rl-header__error {
  font-size: 0.8rem;
  color: var(--color-error, #b91c1c);
}
.btn {
  border-radius: 4px;
  border: 1px solid var(--color-border, #d0d7de);
  background: transparent;
  padding: 0.25rem 0.6rem;
  cursor: pointer;
  font-size: 0.85rem;
}
select {
  font-size: 0.9rem;
  padding: 0.25rem 0.5rem;
}
</style>
