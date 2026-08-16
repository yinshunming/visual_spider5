<script setup lang="ts">
/**
 * ListPreviewPanel 子组件（M4-6 #36 / spec §D9）。
 *
 * <p>列表模式受限预览：调 {@code visualSessionApi.previewList(definition)} 取最多 20 条
 * preview，渲染匹配数 + 每条字段 outcome + diagnostics。薄包装契约：{@link ListPreviewResult}
 * 是前端 DTO，与 {@code com.visualspider.visualbrowser.api.ListPreviewResponse} 字段一一对应。
 */
import { ref } from 'vue'
import { visualSessionApi } from '../../api/visualSession'
import type { ListPreviewItem, ListPreviewResult, TaskDefinition } from '../../contracts/visualSession'

const props = defineProps<{
  sessionId: string
  definition: TaskDefinition
}>()

const result = ref<ListPreviewResult | null>(null)
const loading = ref(false)
const errorMsg = ref<string | null>(null)

async function runPreview(): Promise<void> {
  loading.value = true
  errorMsg.value = null
  try {
    result.value = await visualSessionApi.previewList(props.sessionId, props.definition)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function outcomeValue(out: { rawValue: string | null; cleanedValue: string | null }): string {
  if (out.cleanedValue !== null) {
    return out.cleanedValue
  }
  if (out.rawValue !== null) {
    return out.rawValue
  }
  return '∅'
}

function rowFirstTitle(row: ListPreviewItem): string {
  for (const out of row.fieldOutcomes) {
    if (out.cleanedValue !== null && out.cleanedValue.length > 0) {
      return out.cleanedValue
    }
    if (out.rawValue !== null && out.rawValue.length > 0) {
      return out.rawValue
    }
  }
  return '∅'
}

function isEmpty(out: { isEmpty: boolean }): boolean {
  return out.isEmpty
}
</script>

<template>
  <section class="list-preview">
    <header>
      <h3>列表预览（最多 20 条）</h3>
      <button
        data-test="preview-trigger"
        :disabled="loading || !sessionId"
        @click="runPreview"
      >
        {{ loading ? '预览中…' : '运行预览' }}
      </button>
    </header>

    <p v-if="errorMsg" class="list-preview__err">错误：{{ errorMsg }}</p>

    <p v-if="result" class="list-preview__count">
      共 {{ result.totalMatchCount }} 条匹配，预览前 {{ result.previews.length }} 条
    </p>

    <p
      v-if="result && result.previews.length === 0"
      data-test="preview-empty"
      class="list-preview__warn"
    >
      未匹配
    </p>

    <ul v-if="result && result.previews.length > 0" class="list-preview__rows">
      <li v-for="(row, i) in result.previews" :key="i" class="list-preview__row">
        <strong>#{{ i + 1 }}</strong>
        <ul>
          <li
            v-for="out in row.fieldOutcomes"
            :key="out.fieldName"
            :class="{ 'list-preview__empty': isEmpty(out) }"
          >
            <code>{{ out.fieldName }}</code>:
            <span data-test="preview-row-title">{{ outcomeValue(out) }}</span>
          </li>
        </ul>
        <span data-test="preview-row-primary" hidden>{{ rowFirstTitle(row) }}</span>
      </li>
    </ul>

    <details
      v-if="result && result.diagnostics.length > 0"
      data-test="preview-diagnostics"
    >
      <summary>诊断（{{ result.diagnostics.length }}）</summary>
      <ul>
        <li v-for="(d, i) in result.diagnostics" :key="i">
          <code>{{ d.code }}</code> {{ d.fieldName ?? '' }} {{ d.userMessage }}
        </li>
      </ul>
    </details>
  </section>
</template>

<style scoped>
.list-preview {
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
  padding: 0.5rem 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  justify-content: space-between;
}
header h3 {
  margin: 0;
  font-size: 1rem;
}
button {
  padding: 0.25rem 0.6rem;
  font-size: 0.85rem;
  border: 1px solid var(--color-border, #d0d7de);
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
}
button:disabled { opacity: 0.5; cursor: not-allowed; }
.list-preview__count {
  margin: 0;
  font-size: 0.85rem;
  color: var(--color-muted, #6b7280);
}
.list-preview__warn {
  color: #b45309;
  margin: 0;
  font-size: 0.85rem;
}
.list-preview__err {
  color: var(--color-error, #b91c1c);
  font-size: 0.85rem;
  margin: 0;
}
.list-preview__rows {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.list-preview__row {
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 4px;
  padding: 0.4rem 0.6rem;
  background: var(--color-surface, #f9fafb);
}
.list-preview__row strong {
  font-size: 0.8rem;
  color: var(--color-muted, #6b7280);
}
.list-preview__row ul {
  list-style: none;
  margin: 0.25rem 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  font-size: 0.85rem;
}
.list-preview__empty {
  color: var(--color-muted, #6b7280);
  font-style: italic;
}
</style>