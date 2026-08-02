<script setup lang="ts">
/**
 * M3-6 #28 导出按钮（spec §D18 "CSV/JSON 流式下载"）。
 *
 * 借由 {@link downloadRunExport} 触发浏览器下载；失败抛错在本地 toast 区域展示。
 */
import { ref } from 'vue'
import { downloadRunExport } from '../../api/run'
import { ApiError } from '../../http'

const props = defineProps<{
  runId: number
}>()

const busy = ref(false)
const error = ref<string | null>(null)

async function download(format: 'csv' | 'json'): Promise<void> {
  busy.value = true
  error.value = null
  try {
    await downloadRunExport(props.runId, format)
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status} ${e.message}` : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <span class="export-group">
    <button class="btn btn--ghost" :disabled="busy" @click="download('csv')">CSV</button>
    <button class="btn btn--ghost" :disabled="busy" @click="download('json')">JSON</button>
    <span v-if="error" class="export-group__error">{{ error }}</span>
  </span>
</template>

<style scoped>
.export-group {
  display: inline-flex;
  gap: 0.25rem;
  align-items: center;
}
.btn {
  background: transparent;
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
  padding: 0.2rem 0.5rem;
  cursor: pointer;
  font-size: 0.8rem;
}
.btn:hover { background: var(--color-bg, #f7f8fa); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.export-group__error {
  color: var(--color-error, #b91c1c);
  font-size: 0.75rem;
}
</style>
