<script setup lang="ts">
import { computed } from 'vue'
import type { LifecycleState } from '../../contracts/visualSession'

const props = defineProps<{ lifecycle: LifecycleState }>()
const label = computed(() => {
  switch (props.lifecycle) {
    case 'ACTIVE':
      return '活跃'
    case 'IDLE_CLOSING':
      return '空闲关闭中'
    case 'MAX_REACHED_CLOSING':
      return '超时关闭中'
    case 'USER_CLOSING':
      return '用户关闭中'
    case 'CLOSED':
      return '已关闭'
  }
})
const tone = computed(() => {
  switch (props.lifecycle) {
    case 'ACTIVE':
      return 'ok'
    case 'CLOSED':
      return 'muted'
    default:
      return 'warn'
  }
})
</script>

<template>
  <span :class="['badge', tone]">{{ label }}</span>
</template>

<style scoped>
.badge {
  padding: 0.125rem 0.5rem;
  border-radius: 999px;
  font-size: 0.85rem;
  border: 1px solid currentColor;
}
.ok {
  color: var(--color-ok, #137333);
}
.warn {
  color: var(--color-warn, #b06000);
}
.muted {
  color: var(--color-text-secondary, #57606a);
}
</style>