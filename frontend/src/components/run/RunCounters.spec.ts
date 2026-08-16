/**
 * RunCounters 子组件测试（#36 / spec §D17 / §D18）。
 *
 *  <p>覆盖：
 *  <ul>
 *    <li>四计数 raw / dedup / final / fail 显示</li>
 *    <li>LIST 模式强调（边框颜色变化）</li>
 *    <li>SINGLE_PAGE 模式 fallback（dedup/fail 显式 0）</li>
 *  </ul>
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RunCounters from './RunCounters.vue'

describe('RunCounters.vue (#36)', () => {
  it('渲染四计数标签 + 数值', () => {
    const wrapper = mount(RunCounters, {
      props: { raw: 12, dedup: 3, final: 9, fail: 0, mode: 'LIST' },
    })
    expect(wrapper.text()).toContain('原始 12')
    expect(wrapper.text()).toContain('去重 3')
    expect(wrapper.text()).toContain('最终 9')
    expect(wrapper.text()).toContain('失败 0')
  })

  it('LIST 模式 → list-mode class 启用', () => {
    const wrapper = mount(RunCounters, {
      props: { raw: 0, dedup: 0, final: 0, fail: 0, mode: 'LIST' },
    })
    expect(wrapper.classes()).toContain('run-counters--list')
  })

  it('SINGLE_PAGE 模式 → 不启用 list-mode class', () => {
    const wrapper = mount(RunCounters, {
      props: { raw: 1, dedup: 0, final: 1, fail: 0, mode: 'SINGLE_PAGE' },
    })
    expect(wrapper.classes()).not.toContain('run-counters--list')
  })

  it('mode 缺省 → 不启用 list-mode class', () => {
    const wrapper = mount(RunCounters, {
      props: { raw: 1, dedup: 0, final: 1, fail: 0 },
    })
    expect(wrapper.classes()).not.toContain('run-counters--list')
  })

  it('fail > 0 → fail 计数高亮（红色）', () => {
    const wrapper = mount(RunCounters, {
      props: { raw: 5, dedup: 0, final: 3, fail: 2, mode: 'LIST' },
    })
    expect(wrapper.find('[data-test="counter-fail"]').classes()).toContain('run-counters__cell--danger')
  })
})