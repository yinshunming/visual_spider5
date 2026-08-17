<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ frameUrl: string | null }>()
const dataUrl = computed(() => props.frameUrl)
</script>

<template>
  <div class="frame">
    <img v-if="dataUrl" :src="dataUrl" alt="远程浏览器画面" />
    <div v-else class="placeholder">等待帧…</div>
  </div>
</template>

<style scoped>
.frame {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-canvas, #1f2328);
  min-height: 320px;
}
.frame img {
  max-width: 100%;
  max-height: 720px;
  object-fit: contain;
}
.placeholder {
  color: var(--color-text-muted, #9aa0a6);
}
</style>