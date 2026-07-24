<script setup lang="ts">
import { ref } from 'vue'
import type { FieldDefinition } from '../../contracts/visualSession'

const props = defineProps<{ taskId: number }>()
const fields = ref<FieldDefinition[]>([
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
])
</script>

<template>
  <section class="fields">
    <h3>字段（M2-5 stub）</h3>
    <p class="hint">taskId {{ props.taskId }}。具体编辑逻辑将在 M3+ 实现。</p>
    <ul>
      <li v-for="f in fields" :key="f.name">
        <code>{{ f.name }}</code>
        <span class="meta">{{ f.source }} · {{ f.selector ?? '(no selector)' }}</span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.fields {
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
  padding: 0.5rem 0.75rem;
}
.fields h3 {
  margin: 0 0 0.25rem 0;
  font-size: 1rem;
}
.hint {
  margin: 0;
  font-size: 0.85rem;
  color: var(--color-text-secondary, #57606a);
}
ul {
  margin: 0.25rem 0 0 0;
  padding-left: 1.25rem;
}
.meta {
  color: var(--color-text-secondary, #57606a);
  font-size: 0.8rem;
  margin-left: 0.5rem;
}
</style>