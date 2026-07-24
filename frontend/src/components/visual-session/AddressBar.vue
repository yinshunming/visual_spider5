<script setup lang="ts">
const props = defineProps<{ url: string | null }>()
</script>

<template>
  <div class="address">
    <span class="label">URL</span>
    <code>{{ props.url ?? '—' }}</code>
  </div>
</template>

<style scoped>
.address {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.label {
  color: var(--color-text-secondary, #57606a);
}
code {
  word-break: break-all;
}
</style>