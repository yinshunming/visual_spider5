<script setup lang="ts">
/**
 * M3-6 #28 任务列表入口视图。
 *
 * 在 M3-6 之前没有任务 UI；spec §D18 要求"任务详情/列表加启动运行入口"。
 * 此 view 提供一个最小列表（只读，不改任务），列出当前用户的任务，
 * 在 READY 的卡片下显示"启动运行"按钮触发 {@code POST /api/runs}，跳转
 * {@link RunDetailView}。
 *
 * 该 view 是为达成 #28 acceptance 而做的"启动入口"，M2 后续 ticket
 * （如果有）会替换为完整任务编辑 UI，本 view 只读不写。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { tasksApi } from '../api/tasks'
import { runApi, downloadRunExport } from '../api/run'
import type { TaskSummary } from '../contracts/task'
import { ApiError } from '../http'

const router = useRouter()
const tasks = ref<TaskSummary[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const startingTaskId = ref<number | null>(null)

async function refresh(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    tasks.value = await tasksApi.list()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function startRun(taskId: number): Promise<void> {
  startingTaskId.value = taskId
  try {
    const res = await runApi.start({ taskId })
    await router.push({ name: 'run-detail', params: { id: String(res.runId) } })
  } catch (e) {
    if (e instanceof ApiError) {
      error.value = `启动失败：${e.status} ${e.message}`
    } else {
      error.value = e instanceof Error ? e.message : String(e)
    }
  } finally {
    startingTaskId.value = null
  }
}

async function quickExport(taskId: number, format: 'csv' | 'json'): Promise<void> {
  // 占位：M3-6 主要看 starts；任务维度的导出走 run 维度，没 runId 时退化为无操作。
  // 这里保留 API 调用方式以便上层可以接入；当前未持久化 task→lastRun 映射。
  void taskId
  void format
  void downloadRunExport
}

function canStart(task: TaskSummary): boolean {
  return task.status === 'READY'
}

onMounted(refresh)
</script>

<template>
  <div class="task-list-view">
    <header class="tl-head">
      <h2>任务</h2>
      <button class="btn btn--ghost" :disabled="loading" @click="refresh">刷新</button>
    </header>
    <div v-if="error" class="tl-error">错误：{{ error }}</div>
    <div v-if="loading && tasks.length === 0" class="tl-loading">加载中…</div>
    <ul v-else class="tl-list">
      <li v-for="task in tasks" :key="task.id" class="tl-card">
        <div class="tl-card__head">
          <h3>{{ task.name }}</h3>
          <span class="tl-card__id">#{{ task.id }}</span>
          <span class="tl-card__mode">{{ task.mode }}</span>
          <span class="tl-card__status" :data-status="task.status">{{ task.status }}</span>
        </div>
        <div class="tl-card__meta">
          版本 {{ task.version }} · 更新于 {{ task.updatedAt }}
        </div>
        <div class="tl-card__actions">
          <button
            class="btn btn--primary"
            :disabled="!canStart(task) || startingTaskId === task.id"
            :title="canStart(task) ? '启动一次新的运行' : '仅 READY 任务可启动'"
            @click="startRun(task.id)"
          >
            {{ startingTaskId === task.id ? '启动中…' : '启动运行' }}
          </button>
          <button
            v-if="canStart(task)"
            class="btn btn--ghost"
            disabled
            title="任务维度导出需要已知 run id；M3-6 入口在运行详情页"
          >
            导出（占位）
          </button>
        </div>
      </li>
      <li v-if="tasks.length === 0 && !loading" class="tl-empty">暂无任务（请先到配置页创建一个 READY 任务）</li>
    </ul>
  </div>
</template>

<style scoped>
.task-list-view {
  max-width: 960px;
  margin: 0 auto;
  padding: 1.5rem;
}
.tl-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 1rem;
}
.tl-head h2 { margin: 0; font-size: 1.4rem; }
.tl-error {
  color: var(--color-error, #b91c1c);
  padding: 0.5rem 0.75rem;
  background: #ffebee;
  border-left: 4px solid var(--color-error, #b91c1c);
  border-radius: 4px;
  margin-bottom: 0.75rem;
  font-size: 0.9rem;
}
.tl-loading { color: var(--color-muted, #6b7280); padding: 1rem 0; }
.tl-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 0.75rem;
}
.tl-card {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 6px;
  padding: 0.75rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.tl-card__head {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.tl-card__head h3 { margin: 0; font-size: 1rem; }
.tl-card__id {
  font-family: ui-monospace, SFMono-Regular, monospace;
  font-size: 0.8rem;
  color: var(--color-muted, #6b7280);
}
.tl-card__mode {
  font-size: 0.75rem;
  padding: 0.1rem 0.4rem;
  background: var(--color-bg, #f7f8fa);
  border-radius: 0.75rem;
}
.tl-card__status {
  font-size: 0.75rem;
  font-weight: 600;
  padding: 0.1rem 0.5rem;
  border-radius: 0.75rem;
}
.tl-card__status[data-status='READY'] {
  background: #e8f5e9;
  color: #1b5e20;
  border: 1px solid #66bb6a;
}
.tl-card__status[data-status='DRAFT'] {
  background: #fff8e1;
  color: #8a6d00;
  border: 1px solid #f5c518;
}
.tl-card__meta {
  font-size: 0.8rem;
  color: var(--color-muted, #6b7280);
}
.tl-card__actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.btn {
  border-radius: 4px;
  border: 1px solid var(--color-border, #d0d7de);
  background: transparent;
  padding: 0.35rem 0.7rem;
  cursor: pointer;
  font-size: 0.85rem;
}
.btn--primary { background: var(--color-accent, #2563eb); color: #fff; border-color: transparent; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.tl-empty { color: var(--color-muted, #6b7280); padding: 1rem; text-align: center; }
</style>
