<script setup lang="ts">
/**
 * 预览面板（M2-5 #21 单页预览；M4-6 #36 扩列表预览）。
 *
 * <p>单页（{@code mode === 'SINGLE_PAGE'}）：调 {@code visualSessionApi.preview} 拿字段 outcome。
 * <br>列表（{@code mode === 'LIST'}）：挂 {@link ListPreviewPanel} 走 {@code previewList}。
 */
import { ref } from 'vue'
import { visualSessionApi } from '../../api/visualSession'
import ListPreviewPanel from './ListPreviewPanel.vue'
import type { PreviewResult, TaskDefinition, TaskMode } from '../../contracts/visualSession'

const props = defineProps<{ sessionId: string; definition: TaskDefinition; mode: TaskMode }>()

const result = ref<PreviewResult | null>(null)
const loading = ref(false)

async function runPreview(): Promise<void> {
  if (!props.sessionId) {
    return
  }
  loading.value = true
  try {
    result.value = await visualSessionApi.preview(props.sessionId, props.definition)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <ListPreviewPanel
    v-if="mode === 'LIST'"
    :session-id="sessionId"
    :definition="definition"
  />
  <section v-else class="preview">
    <h3>预览</h3>
    <button :disabled="loading || !sessionId" @click="runPreview">
      {{ loading ? '运行中…' : '运行预览' }}
    </button>
    <ul v-if="result">
      <li v-for="o in result.fieldOutcomes" :key="o.fieldName">
        <code>{{ o.fieldName }}</code>
        raw: <span>{{ o.rawValue ?? '∅' }}</span> →
        cleaned: <strong>{{ o.cleanedValue ?? '∅' }}</strong>
        <span v-if="o.isEmpty"> (空)</span>
      </li>
    </ul>
    <details v-if="result && result.diagnostics.length">
      <summary>诊断 ({{ result.diagnostics.length }})</summary>
      <ul>
        <li v-for="(d, i) in result.diagnostics" :key="i">
          <code>{{ d.code }}</code> {{ d.fieldName ?? '' }} {{ d.userMessage }}
        </li>
      </ul>
    </details>
  </section>
</template>

<style scoped>
.preview {
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
  padding: 0.5rem 0.75rem;
}
.preview ul {
  margin: 0.25rem 0 0 0;
  padding-left: 1.25rem;
}
</style>