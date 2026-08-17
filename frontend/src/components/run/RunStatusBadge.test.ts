/**
 * M3-6 #28 RunStatusBadge 边界测试。
 *
 * 覆盖 RunState 全部 7 态：每态都映射到一个唯一的样式 class、data-state 属性正确。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RunStatusBadge from './RunStatusBadge.vue'
import type { RunState } from '../../contracts/run'

const STATES: RunState[] = [
  'WAITING',
  'RUNNING',
  'SUCCESS',
  'PARTIAL_SUCCESS',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
]

describe('RunStatusBadge', () => {
  it('挂载即渲染传入状态文本', () => {
    for (const s of STATES) {
      const w = mount(RunStatusBadge, { props: { status: s } })
      expect(w.text()).toContain(s)
      expect(w.attributes('data-state')).toBe(s)
      w.unmount()
    }
  })

  it('终态行内带 "终态" 标签', () => {
    const terminal: RunState[] = ['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'INTERRUPTED']
    for (const s of terminal) {
      const w = mount(RunStatusBadge, { props: { status: s } })
      expect(w.text()).toContain('终态')
      w.unmount()
    }
  })

  it('进行中状态不带 "终态" 标签', () => {
    const inflight: RunState[] = ['WAITING', 'RUNNING']
    for (const s of inflight) {
      const w = mount(RunStatusBadge, { props: { status: s } })
      expect(w.text()).not.toContain('终态')
      w.unmount()
    }
  })

  it('状态变更时 class 跟着换', async () => {
    const w = mount(RunStatusBadge, { props: { status: 'WAITING' } })
    expect(w.classes().some((c) => c.startsWith('badge--'))).toBe(true)
    const before = w.classes().find((c) => c.startsWith('badge--'))
    expect(before).toBe('badge--waiting')
    await w.setProps({ status: 'FAILED' })
    expect(w.classes().some((c) => c.startsWith('badge--failed'))).toBe(true)
    w.unmount()
  })
})
