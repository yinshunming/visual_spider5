/**
 * ListInferPanel 子组件测试（#36 / spec §D3）。
 *
 *  <p>覆盖：
 *  <ul>
 *    <li>默认空状态 → 不显示 match 数</li>
 *    <li>点击「推断」→ 调 visualSessionApi.infer → 显示 score/matchCount/components</li>
 *    <li>infer.lowConfidence=true → 显示空结果提示</li>
 *    <li>ancestor 调整按钮 → emit update:listItemRule（沿 ancestorPath 上溯 / 下移）</li>
 *    <li>alternatives 选择 → emit update:listItemRule</li>
 *  </ul>
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ListInferPanel from './ListInferPanel.vue'
import type {
  InferRequest,
  InferResponse,
  ListItemRule,
  TaskDefinition,
} from '../../contracts/visualSession'
import { visualSessionApi } from '../../api/visualSession'

const BASE_DEF: TaskDefinition = {
  schemaVersion: 2,
  mode: 'LIST',
  startUrl: 'https://example.com/list',
  viewport: { width: 1280, height: 720 },
  listItemRule: { selector: 'tbody > tr', selectorType: 'CSS' },
  fields: [],
}

function wrapInfer(extra: Partial<InferResponse> = {}): InferResponse {
  return {
    selector: 'tbody > tr',
    selectorType: 'CSS',
    matchCount: 5,
    score: 0.83,
    ancestorPath: [
      { depth: 1, tagAndClass: 'tbody' },
      { depth: 2, tagAndClass: 'table' },
    ],
    components: [
      { name: 'sibling', raw: 0.9, weighted: 0.36, note: null },
    ],
    alternatives: ['tbody > tr', '.list > li'],
    lowConfidence: false,
    ...extra,
  }
}

describe('ListInferPanel.vue (#36)', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('初始状态：不显示 match 数 / score', () => {
    const wrapper = mount(ListInferPanel, {
      props: { sessionId: 's1', definition: BASE_DEF },
    })
    expect(wrapper.text()).not.toContain('命中')
    expect(wrapper.find('[data-test="infer-score"]').exists()).toBe(false)
  })

  it('点击「推断」按钮 → 调 visualSessionApi.infer 并渲染 score / matchCount / components', async () => {
    const inferSpy = vi
      .spyOn(visualSessionApi, 'infer')
      .mockResolvedValue(wrapInfer())
    const wrapper = mount(ListInferPanel, {
      props: { sessionId: 's1', definition: BASE_DEF },
    })
    await wrapper.find('[data-test="infer-trigger"]').trigger('click')
    await flushPromises()
    expect(inferSpy).toHaveBeenCalledTimes(1)
    const arg = inferSpy.mock.calls[0][1] as InferRequest
    expect(arg.x).toBeGreaterThan(0)
    expect(arg.clientWidth).toBe(1280)
    expect(arg.clientHeight).toBe(720)
    expect(wrapper.text()).toContain('命中 5')
    expect(wrapper.text()).toContain('0.83')
    expect(wrapper.find('[data-test="infer-score"]').exists()).toBe(true)
  })

  it('lowConfidence=true → 显示空结果提示，不显示 score 进度', async () => {
    vi.spyOn(visualSessionApi, 'infer').mockResolvedValue(
      wrapInfer({ lowConfidence: true, matchCount: 0, score: 0 }),
    )
    const wrapper = mount(ListInferPanel, {
      props: { sessionId: 's1', definition: BASE_DEF },
    })
    await wrapper.find('[data-test="infer-trigger"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('未命中')
  })

  it('上溯按钮（depth=2 → tbody）→ emit update:listItemRule', async () => {
    vi.spyOn(visualSessionApi, 'infer').mockResolvedValue(wrapInfer())
    const wrapper = mount(ListInferPanel, {
      props: { sessionId: 's1', definition: BASE_DEF },
    })
    await wrapper.find('[data-test="infer-trigger"]').trigger('click')
    await flushPromises()
    // 上溯一级：选 ancestorPath 中 depth=2（最远的祖先）
    await wrapper.find('[data-test="ancestor-up"]').trigger('click')
    const updates = wrapper.emitted('update:listItemRule') ?? []
    expect(updates.length).toBeGreaterThan(0)
    const lastRule = updates[updates.length - 1][0] as ListItemRule
    expect(lastRule.selector).toBe('tbody')
    expect(lastRule.selectorType).toBe('CSS')
  })

  it('alternatives 选择 → emit update:listItemRule', async () => {
    vi.spyOn(visualSessionApi, 'infer').mockResolvedValue(wrapInfer())
    const wrapper = mount(ListInferPanel, {
      props: { sessionId: 's1', definition: BASE_DEF },
    })
    await wrapper.find('[data-test="infer-trigger"]').trigger('click')
    await flushPromises()
    const alt = wrapper.findAll('[data-test="infer-alternative"]')
    expect(alt.length).toBeGreaterThan(0)
    await alt[1].trigger('click') // '.list > li'
    const updates = wrapper.emitted('update:listItemRule') ?? []
    const lastRule = updates[updates.length - 1][0] as ListItemRule
    expect(lastRule.selector).toBe('.list > li')
  })

  it('定义中已存在 listItemRule → infer 前在状态里预填', async () => {
    const wrapper = mount(ListInferPanel, {
      props: {
        sessionId: 's1',
        definition: {
          ...BASE_DEF,
          listItemRule: { selector: 'table > tbody > tr', selectorType: 'CSS' },
        },
      },
    })
    const input = wrapper.find('input[data-test="list-item-selector"]')
    expect((input.element as HTMLInputElement).value).toBe('table > tbody > tr')
  })
})