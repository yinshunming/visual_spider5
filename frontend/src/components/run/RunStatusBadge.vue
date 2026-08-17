<script setup lang="ts">
/**
 * M3-6 #28 状态徽章（spec §D18 表格列 "状态" / 详情头）。
 *
 * 颜色按 7 态分组：进行中（WAITING/RUNNING）/ 终态（SUCCESS / PARTIAL_SUCCESS /
 * FAILED / CANCELLED / INTERRUPTED）。
 */
import type { RunState } from '../../contracts/run'

defineProps<{
  status: RunState
}>()

const TERMINAL: ReadonlySet<RunState> = new Set([
  'SUCCESS',
  'PARTIAL_SUCCESS',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
])

function classFor(status: RunState): string {
  switch (status) {
    case 'WAITING':
      return 'badge badge--waiting'
    case 'RUNNING':
      return 'badge badge--running'
    case 'SUCCESS':
      return 'badge badge--success'
    case 'PARTIAL_SUCCESS':
      return 'badge badge--partial'
    case 'FAILED':
      return 'badge badge--failed'
    case 'CANCELLED':
      return 'badge badge--cancelled'
    case 'INTERRUPTED':
      return 'badge badge--interrupted'
  }
}
</script>

<template>
  <span :class="classFor(status)" :data-state="status">
    {{ status }}
    <span v-if="TERMINAL.has(status)" class="badge__tag">终态</span>
  </span>
</template>

<style scoped>
.badge {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.15rem 0.5rem;
  border-radius: 0.75rem;
  font-size: 0.8rem;
  font-weight: 600;
  border: 1px solid transparent;
}
.badge__tag {
  font-size: 0.65rem;
  font-weight: 500;
  opacity: 0.7;
}
.badge--waiting {
  background: #fff8e1;
  color: #8a6d00;
  border-color: #f5c518;
}
.badge--running {
  background: #e3f2fd;
  color: #0d47a1;
  border-color: #64b5f6;
}
.badge--success {
  background: #e8f5e9;
  color: #1b5e20;
  border-color: #66bb6a;
}
.badge--partial {
  background: #f3e5f5;
  color: #6a1b9a;
  border-color: #ba68c8;
}
.badge--failed {
  background: #ffebee;
  color: #b71c1c;
  border-color: #ef5350;
}
.badge--cancelled {
  background: #f5f5f5;
  color: #616161;
  border-color: #bdbdbd;
}
.badge--interrupted {
  background: #fff3e0;
  color: #e65100;
  border-color: #fb8c00;
}
</style>
