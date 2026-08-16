<script setup lang="ts">
/**
 * RunCounters 子组件（M4-6 #36 / spec §D17 / §D18）。
 *
 * <p>运行详情四计数独立面板：raw / dedup / final / fail，从 {@link ProgressPanel} 抽出。
 * LIST 模式强调（边框 + 配色）；SINGLE_PAGE 模式 dedup 永远 0（不涉及去重）。
 *
 * <p>数据来源：父 view 把 WS PROGRESS 帧的 recordCountRaw/Dedup/Final/Fail 透传至此。
 * REST fallback 由父 view 拉取 {@link RunDetail} 后同位置。
 */
import { computed } from 'vue'
import type { TaskMode } from '../../contracts/visualSession'

const props = defineProps<{
  raw: number
  dedup: number
  final: number
  fail: number
  mode?: TaskMode
}>()

const isList = computed<boolean>(() => props.mode === 'LIST')
</script>

<template>
  <section
    class="run-counters"
    :class="{ 'run-counters--list': isList }"
    aria-label="运行计数"
  >
    <header>
      <h3>运行计数</h3>
    </header>
    <dl class="run-counters__grid">
      <div class="run-counters__cell">
        <dt>原始 {{ raw }}</dt>
        <dd data-test="counter-raw">{{ raw }}</dd>
      </div>
      <div class="run-counters__cell">
        <dt>去重 {{ dedup }}</dt>
        <dd data-test="counter-dedup">{{ dedup }}</dd>
      </div>
      <div class="run-counters__cell">
        <dt>最终 {{ final }}</dt>
        <dd data-test="counter-final">{{ final }}</dd>
      </div>
      <div
        class="run-counters__cell"
        :class="{ 'run-counters__cell--danger': fail > 0 }"
        data-test="counter-fail"
      >
        <dt>失败 {{ fail }}</dt>
        <dd>{{ fail }}</dd>
      </div>
    </dl>
  </section>
</template>

<style scoped>
.run-counters {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 6px;
  padding: 0.75rem 1rem;
}
.run-counters--list {
  border-color: #fb923c;
  background: #fff7ed;
}
header h3 {
  margin: 0 0 0.5rem;
  font-size: 1rem;
}
.run-counters__grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.5rem 1rem;
  margin: 0;
}
.run-counters__cell {
  border-left: 3px solid var(--color-border, #d0d7de);
  padding-left: 0.5rem;
}
.run-counters__cell dt {
  font-size: 0.7rem;
  text-transform: uppercase;
  color: var(--color-muted, #6b7280);
}
.run-counters__cell dd {
  margin: 0.1rem 0 0;
  font-size: 1.3rem;
  font-weight: 600;
}
.run-counters__cell--danger {
  border-left-color: var(--color-error, #b91c1c);
}
.run-counters__cell--danger dd {
  color: var(--color-error, #b91c1c);
}
</style>