<script setup lang="ts">
import { ref } from 'vue'
import { visualSessionApi } from '../../api/visualSession'
import type { PreviewResult } from '../../contracts/visualSession'

const props = defineProps<{ sessionId: string }>()
const result = ref<PreviewResult | null>(null)
const loading = ref(false)

async function runPreview(): Promise<void> {
  if (!props.sessionId) {
    return
  }
  loading.value = true
  try {
    result.value = await visualSessionApi.preview(props.sessionId, {
      schemaVersion: 1,
      mode: 'SINGLE_PAGE',
      startUrl: 'http://example.com/',
      viewport: { width: 1280, height: 720 },
      fields: [
        {
          name: 'title',
          source: 'VISIBLE_TEXT',
          selector: 'h1',
          attributeName: undefined,
          resultType: 'TEXT',
          trim: 'TRIM',
          regex: undefined,
          required: true,
        },
      ],
    })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="preview">
    <h3>预览</h3>
    <button :disabled="loading || !props.sessionId" @click="runPreview">
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