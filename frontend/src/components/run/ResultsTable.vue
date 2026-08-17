<script setup lang="ts">
/**
 * M3-6 #28 结果表（spec §D17/D18 "服务器分页结果表"）。
 *
 * 服务端 keyset 分页（{@code page/size}）；前端禁止无界加载。
 * 列：sequenceNo + 字段名列（从结果首行 data 推断 key 集合）。
 */
import { computed, ref, watch } from 'vue'
import type { ResultRecord, RunResultsResponse } from '../../contracts/run'

const props = defineProps<{
  runId: number
  fetchPage: (runId: number, page: number, size: number) => Promise<RunResultsResponse>
  pageSize?: number
}>()

const PAGE_SIZE = computed(() => props.pageSize ?? 10)

const page = ref(1)
const items = ref<ResultRecord[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

const columns = computed(() => {
  // 以首条 data 的字段名充当表头；若暂无数据则置空。
  const first = items.value[0]
  if (!first) return []
  return Object.keys(first.data)
})

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE.value)))

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

function gotoPage(p: number): void {
  if (p < 1 || p > totalPages.value) return
  page.value = p
}

watch(
  () => [props.runId, page.value],
  () => {
    void load()
  },
  { immediate: true },
)

defineExpose({ refresh: load })
</script>

<template>
  <section class="results" aria-label="结果表">
    <header class="results__head">
      <h3>结果（{{ total }}）</h3>
      <div class="results__pager">
        <button class="btn" :disabled="page <= 1 || loading" @click="gotoPage(page - 1)">上一页</button>
        <span>{{ page }} / {{ totalPages }}</span>
        <button class="btn" :disabled="page >= totalPages || loading" @click="gotoPage(page + 1)">下一页</button>
      </div>
    </header>
    <div v-if="error" class="results__error">错误：{{ error }}</div>
    <div v-else-if="loading && items.length === 0" class="results__loading">加载中…</div>
    <div v-else-if="items.length === 0" class="results__loading">暂无结果</div>
    <table v-else class="results__table">
      <thead>
        <tr>
          <th>#</th>
          <th v-for="col in columns" :key="col">{{ col }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id">
          <td>{{ row.sequenceNo }}</td>
          <td v-for="col in columns" :key="col">{{ row.data[col] ?? '' }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.results {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 6px;
  padding: 1rem 1.25rem;
}
.results__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 0.5rem;
}
.results__head h3 {
  margin: 0;
  font-size: 1.05rem;
}
.results__pager {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
}
.btn {
  background: transparent;
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
  padding: 0.25rem 0.6rem;
  cursor: pointer;
  font-size: 0.8rem;
}
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn:hover { background: var(--color-bg, #f7f8fa); }
.results__error,
.results__loading {
  color: var(--color-muted, #6b7280);
  padding: 1rem 0;
}
.results__error { color: var(--color-error, #b91c1c); }
.results__table {
  width: 100%;
  border-collapse: collapse;
}
.results__table th,
.results__table td {
  text-align: left;
  padding: 0.35rem 0.5rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
  font-size: 0.85rem;
}
.results__table th {
  background: var(--color-bg, #f7f8fa);
  font-weight: 600;
}
</style>
