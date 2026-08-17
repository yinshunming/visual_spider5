<script setup lang="ts">
/**
 * M3-6 #28 快照只读查看器（spec §D17/D18）。
 *
 * 运行详情页可看运行时固化的 TaskDefinition（start 时固化，运行期间不再变）。
 * 只读展示：序号、字段名、来源、选择器（含 selectorType）、属性、结果类型、
 * 空白策略、正则、required。
 */
import { computed } from 'vue'
import type { RunSnapshotResponse } from '../../contracts/run'

const props = defineProps<{
  snapshot: RunSnapshotResponse | null
  loading: boolean
  error: string | null
}>()

const meta = computed(() => props.snapshot)
const fields = computed(() => props.snapshot?.definition.fields ?? [])
</script>

<template>
  <section class="snap" aria-label="固化任务快照">
    <header class="snap__head">
      <h3>固化任务快照（只读）</h3>
      <span v-if="meta" class="snap__tag">v{{ meta.taskVersion }} · schemaVersion {{ meta.schemaVersion }}</span>
    </header>
    <div v-if="loading" class="snap__loading">加载中…</div>
    <div v-else-if="error" class="snap__error">错误：{{ error }}</div>
    <div v-else-if="!meta" class="snap__loading">暂无快照</div>
    <div v-else class="snap__body">
      <dl class="snap__meta">
        <div><dt>name</dt><dd>{{ meta.name }}</dd></div>
        <div><dt>mode</dt><dd>{{ meta.mode }}</dd></div>
        <div><dt>startUrl</dt><dd class="snap__url">{{ meta.definition.startUrl }}</dd></div>
        <div>
          <dt>extraWaitSeconds</dt>
          <dd>{{ meta.definition.waitPolicy?.extraWaitSeconds ?? 0 }}</dd>
        </div>
      </dl>
      <table class="snap__fields">
        <thead>
          <tr>
            <th>#</th>
            <th>name</th>
            <th>source</th>
            <th>selector / type</th>
            <th>attribute</th>
            <th>resultType</th>
            <th>trim</th>
            <th>regex</th>
            <th>required</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(f, i) in fields" :key="i">
            <td>{{ i + 1 }}</td>
            <td>{{ f.name }}</td>
            <td>{{ f.source }}</td>
            <td>
              <code>{{ f.selector ?? '—' }}</code>
              <span v-if="f.selectorType" class="snap__type">[{{ f.selectorType }}]</span>
            </td>
            <td>{{ f.attributeName ?? '—' }}</td>
            <td>{{ f.resultType }}</td>
            <td>{{ f.trim }}</td>
            <td><code>{{ f.regex ?? '—' }}</code></td>
            <td>{{ f.required ? '是' : '否' }}</td>
          </tr>
          <tr v-if="fields.length === 0">
            <td colspan="9" class="snap__empty">无字段</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.snap {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 6px;
  padding: 1rem 1.25rem;
}
.snap__head {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}
.snap__head h3 {
  margin: 0;
  font-size: 1.05rem;
}
.snap__tag {
  font-size: 0.75rem;
  color: var(--color-muted, #6b7280);
}
.snap__loading,
.snap__error {
  padding: 1rem 0;
  color: var(--color-muted, #6b7280);
}
.snap__error { color: var(--color-error, #b91c1c); }
.snap__meta {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 0.4rem 1.5rem;
  margin-bottom: 0.75rem;
}
.snap__meta dt {
  font-size: 0.7rem;
  text-transform: uppercase;
  color: var(--color-muted, #6b7280);
}
.snap__meta dd {
  margin: 0;
  font-size: 0.85rem;
}
.snap__url {
  font-family: ui-monospace, SFMono-Regular, monospace;
  font-size: 0.8rem;
}
.snap__fields {
  width: 100%;
  border-collapse: collapse;
}
.snap__fields th,
.snap__fields td {
  text-align: left;
  padding: 0.35rem 0.5rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
  font-size: 0.85rem;
}
.snap__fields th {
  background: var(--color-bg, #f7f8fa);
  font-weight: 600;
}
.snap__type {
  font-size: 0.7rem;
  margin-left: 0.25rem;
  color: var(--color-muted, #6b7280);
}
.snap__empty {
  text-align: center;
  color: var(--color-muted, #6b7280);
  padding: 0.75rem;
}
code {
  background: #f1f3f5;
  padding: 0.05em 0.3em;
  border-radius: 3px;
  font-size: 0.85em;
}
</style>
