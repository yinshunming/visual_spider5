<script setup lang="ts">
/**
 * M3-6 #28 事件流（spec §D18 "事件流分页"）。
 *
 * 运行期间的结构化事件（含 WS 推过来的 EVENT）；按 level 着色。
 */
import { ref, watch, computed } from 'vue'
import type { RunEventDto, RunEventsResponse } from '../../contracts/run'

const props = defineProps<{
  runId: number
  fetchPage: (runId: number, page: number, size: number) => Promise<RunEventsResponse>
  pageSize?: number
}>()

const PAGE_SIZE = computed(() => props.pageSize ?? 50)
const page = ref(1)
const items = ref<RunEventDto[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const res = await props.fetchPage(props.runId, page.value, PAGE_SIZE.value)
    items.value = res.items
    total.value = res.total
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.runId, page.value],
  () => {
    void load()
  },
  { immediate: true },
)

function levelClass(level: RunEventDto['level']): string {
  return `event-stream__row event-stream__row--${level.toLowerCase()}`
}

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString()
}

defineExpose({ refresh: load })
</script>

<template>
  <section class="event-stream" aria-label="事件流">
    <header class="event-stream__head">
      <h3>事件流（{{ items.length }} / {{ total }}）</h3>
      <button class="btn" :disabled="loading" @click="load">刷新</button>
    </header>
    <div v-if="error" class="event-stream__error">错误：{{ error }}</div>
    <ul v-else class="event-stream__list">
      <li v-for="ev in items" :key="ev.id" :class="levelClass(ev.level)">
        <span class="event-stream__time">{{ formatTime(ev.createdAt) }}</span>
        <span class="event-stream__level">{{ ev.level }}</span>
        <span v-if="ev.stage" class="event-stream__stage">[{{ ev.stage }}]</span>
        <span class="event-stream__msg">{{ ev.message }}</span>
        <code v-if="ev.errorCode" class="event-stream__code">{{ ev.errorCode }}</code>
      </li>
      <li v-if="items.length === 0 && !loading" class="event-stream__row event-stream__row--empty">
        暂无事件
      </li>
    </ul>
  </section>
</template>

<style scoped>
.event-stream {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 6px;
  padding: 1rem 1.25rem;
}
.event-stream__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 0.5rem;
}
.event-stream__head h3 {
  margin: 0;
  font-size: 1.05rem;
}
.btn {
  background: transparent;
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
  padding: 0.25rem 0.6rem;
  cursor: pointer;
  font-size: 0.8rem;
}
.event-stream__error { color: var(--color-error, #b91c1c); }
.event-stream__list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  max-height: 360px;
  overflow: auto;
}
.event-stream__row {
  display: flex;
  gap: 0.5rem;
  align-items: baseline;
  font-size: 0.85rem;
  padding: 0.3rem 0.5rem;
  border-radius: 4px;
  background: var(--color-bg, #f7f8fa);
}
.event-stream__row--info { border-left: 3px solid #1976d2; }
.event-stream__row--warn { border-left: 3px solid #f59e0b; background: #fffbeb; }
.event-stream__row--error { border-left: 3px solid #b00020; background: #ffebee; }
.event-stream__row--empty { background: transparent; color: var(--color-muted, #6b7280); }
.event-stream__time {
  font-family: ui-monospace, SFMono-Regular, monospace;
  font-size: 0.75rem;
  color: var(--color-muted, #6b7280);
  white-space: nowrap;
}
.event-stream__level {
  font-weight: 600;
  font-size: 0.75rem;
}
.event-stream__stage {
  font-size: 0.75rem;
  color: var(--color-muted, #6b7280);
}
.event-stream__msg { flex: 1 1 auto; }
.event-stream__code {
  background: #f1f3f5;
  padding: 0.1em 0.4em;
  border-radius: 3px;
  font-size: 0.75rem;
}
</style>
