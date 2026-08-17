<script setup lang="ts">
import { ref, watch } from 'vue'
import { visualSessionApi } from '../../api/visualSession'
import type { SelectorOutcome } from '../../contracts/visualSession'

const props = defineProps<{ sessionId: string }>()
const selector = ref('h1')
const outcomes = ref<SelectorOutcome[]>([])
let timer: number | null = null

watch(selector, () => {
  if (timer !== null) {
    window.clearTimeout(timer)
  }
  timer = window.setTimeout(runValidate, 300)
})

async function runValidate(): Promise<void> {
  if (!props.sessionId || !selector.value.trim()) {
    outcomes.value = []
    return
  }
  try {
    const response = await visualSessionApi.validateSelectors(props.sessionId, {
      selectors: [{ type: 'css', selector: selector.value.trim() }],
    })
    outcomes.value = response.outcomes
  } catch (e) {
    outcomes.value = [
      {
        selector: selector.value,
        type: 'css',
        valid: false,
        matchCount: 0,
        error: e instanceof Error ? e.message : String(e),
        matchedRanges: [],
      },
    ]
  }
}
</script>

<template>
  <section class="live-feedback">
    <h3>选择器校验 (300ms 防抖)</h3>
    <input v-model="selector" type="text" placeholder="例如：h1, .title, a[href]" />
    <ul v-if="outcomes.length">
      <li v-for="o in outcomes" :key="o.selector">
        <code>{{ o.selector }}</code>
        <span :class="o.valid ? 'ok' : 'err'">
          {{ o.valid ? `匹配 ${o.matchCount}` : `错误: ${o.error ?? 'invalid'}` }}
        </span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.live-feedback {
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
  padding: 0.5rem 0.75rem;
}
.live-feedback input {
  width: 100%;
  box-sizing: border-box;
  padding: 0.25rem;
}
.ok {
  color: var(--color-ok, #137333);
}
.err {
  color: var(--color-error, #b00020);
}
</style>