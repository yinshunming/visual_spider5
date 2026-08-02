<script setup lang="ts">
/**
 * M3-6 #28 列表行表（spec §D18 表格）。
 *
 * 列：runId / 任务名 / 状态 / 计数 / 开始·结束 / 取消 / 导出下拉。
 * 取消按钮仅在 WAITING/RUNNING 时显示；其余状态显示占位。
 */
import { computed } from 'vue'
import RunStatusBadge from './RunStatusBadge.vue'
import ExportButtons from './ExportButtons.vue'
import type { RunSummary } from '../../contracts/run'

const props = defineProps<{
  items: readonly RunSummary[]
  loading?: boolean
}>()

const emit = defineEmits<{
  (e: 'cancel', runId: number): void
  (e: 'open', runId: number): void
}>()

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  // 2026-08-02 12:34:56（按运行所在时区显示，简化为本地时区）
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

function isCancellable(status: RunSummary['status']): boolean {
  return status === 'WAITING' || status === 'RUNNING'
}

const hasItems = computed(() => props.items.length > 0)
</script>

<template>
  <table class="run-table">
    <thead>
      <tr>
        <th>runId</th>
        <th>任务 ID</th>
        <th>状态</th>
        <th>页 / 记录 / 失败</th>
        <th>开始</th>
        <th>结束</th>
        <th class="run-table__actions">操作</th>
      </tr>
    </thead>
    <tbody v-if="hasItems">
      <tr v-for="row in items" :key="row.runId">
        <td>
          <a href="#" @click.prevent="emit('open', row.runId)">#{{ row.runId }}</a>
        </td>
        <td>{{ row.taskId }}</td>
        <td><RunStatusBadge :status="row.status" /></td>
        <td>{{ row.pageCount }} / {{ row.recordCountFinal }} / {{ row.failCount }}</td>
        <td>{{ formatTime(row.startedAt) }}</td>
        <td>{{ formatTime(row.finishedAt) }}</td>
        <td class="run-table__actions">
          <button
            v-if="isCancellable(row.status)"
            class="btn btn--danger"
            :disabled="props.loading"
            @click="emit('cancel', row.runId)"
          >
            取消
          </button>
          <span v-else class="muted">—</span>
          <ExportButtons :run-id="row.runId" />
        </td>
      </tr>
    </tbody>
    <tbody v-else>
      <tr>
        <td colspan="7" class="run-table__empty">
          <span v-if="props.loading">加载中…</span>
          <span v-else>暂无运行</span>
        </td>
      </tr>
    </tbody>
  </table>
</template>

<style scoped>
.run-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--color-surface, #fff);
}
.run-table th,
.run-table td {
  text-align: left;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
  font-size: 0.9rem;
}
.run-table th {
  background: var(--color-bg, #f7f8fa);
  font-weight: 600;
}
.run-table__actions {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
}
.run-table__empty {
  text-align: center;
  color: var(--color-muted, #6b7280);
  padding: 2rem 0;
}
.muted { color: var(--color-muted, #6b7280); }
.btn {
  border-radius: 4px;
  border: none;
  padding: 0.3rem 0.7rem;
  cursor: pointer;
  font-size: 0.85rem;
}
.btn--danger { background: #b00020; color: #fff; }
.btn--danger:hover { filter: brightness(0.92); }
a { color: var(--color-accent, #2563eb); text-decoration: none; }
a:hover { text-decoration: underline; }
</style>
