<script setup lang="ts">
type Mode = 'BROWSE' | 'SELECT'
const props = defineProps<{ mode: Mode }>()
const emit = defineEmits<{ (e: 'switch', mode: Mode): void }>()
</script>

<template>
  <fieldset class="modes">
    <legend>模式</legend>
    <label>
      <input
        type="radio"
        :checked="props.mode === 'BROWSE'"
        @change="emit('switch', 'BROWSE')"
      />
      浏览
    </label>
    <label>
      <input
        type="radio"
        :checked="props.mode === 'SELECT'"
        @change="emit('switch', 'SELECT')"
      />
      选择
    </label>
  </fieldset>
</template>

<style scoped>
.modes {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  padding: 0.5rem;
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
}
.modes label {
  display: flex;
  gap: 0.25rem;
  align-items: center;
}
</style>