/**
 * ListPreviewPanel 子组件测试（#36 / spec §D9）。
 *
 *  <p>覆盖：
 *  <ul>
 *    <li>点击「预览」→ 调 visualSessionApi.previewList</li>
 *    <li>渲染 ≤ 20 行 preview + totalMatchCount</li>
 *    <li>空结果（previews.length=0）显示提示</li>
 *    <li>diagnostics 含 LIST_ITEM_RULE_NO_MATCH → 错误回显</li>
 *  </ul>
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ListPreviewPanel from './ListPreviewPanel.vue'
import type { ListPreviewItem, ListPreviewResult, TaskDefinition } from '../../contracts/visualSession'
import { visualSessionApi } from '../../api/visualSession'

const DEF: TaskDefinition = {
  schemaVersion: 2,
  mode: 'LIST',
  startUrl: 'https://example.com/list',
  viewport: { width: 1280, height: 720 },
  listItemRule: { selector: 'tbody > tr', selectorType: 'CSS' },
  fields: [
    { name: 'title', source: 'VISIBLE_TEXT', selector: '.title', resultType: 'TEXT', trim: 'TRIM', required: true },
  ],
}

function preview(title: string): ListPreviewItem {
  return {
    fieldOutcomes: [
      { fieldName: 'title', rawValue: title, cleanedValue: title, isEmpty: false },
    ],
    diagnostics: [],
  }
}

function wrapResult(previews: ListPreviewItem[]): ListPreviewResult {
  return {
    previews,
    totalMatchCount: previews.length,
    diagnostics: [],
  }
}

describe('ListPreviewPanel.vue (#36)', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('点击「预览」→ 调 visualSessionApi.previewList', async () => {
    const spy = vi
      .spyOn(visualSessionApi, 'previewList')
      .mockResolvedValue(wrapResult([preview('Alpha'), preview('Beta')]))
    const wrapper = mount(ListPreviewPanel, {
      props: { sessionId: 's1', definition: DEF },
    })
    await wrapper.find('[data-test="preview-trigger"]').trigger('click')
    await flushPromises()
    expect(spy).toHaveBeenCalledTimes(1)
    expect(spy.mock.calls[0][0]).toBe('s1')
  })

  it('渲染 totalMatchCount + 每条 preview.title', async () => {
    vi.spyOn(visualSessionApi, 'previewList').mockResolvedValue(
      wrapResult([preview('Alpha'), preview('Beta'), preview('Gamma')]),
    )
    const wrapper = mount(ListPreviewPanel, {
      props: { sessionId: 's1', definition: DEF },
    })
    await wrapper.find('[data-test="preview-trigger"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('共 3 条匹配')
    const titles = wrapper.findAll('[data-test="preview-row-title"]')
    expect(titles.length).toBe(3)
    expect(titles[0].text()).toBe('Alpha')
    expect(titles[2].text()).toBe('Gamma')
  })

  it('空结果 → 显示「未匹配」提示', async () => {
    vi.spyOn(visualSessionApi, 'previewList').mockResolvedValue(
      wrapResult([]),
    )
    const wrapper = mount(ListPreviewPanel, {
      props: { sessionId: 's1', definition: DEF },
    })
    await wrapper.find('[data-test="preview-trigger"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('未匹配')
  })

  it('diagnostics 含 LIST_ITEM_RULE_NO_MATCH → 错误回显', async () => {
    vi.spyOn(visualSessionApi, 'previewList').mockResolvedValue({
      previews: [],
      totalMatchCount: 0,
      diagnostics: [
        { code: 'LIST_ITEM_RULE_NO_MATCH', fieldName: undefined, userMessage: 'listItemRule 命中 0 项' },
      ],
    })
    const wrapper = mount(ListPreviewPanel, {
      props: { sessionId: 's1', definition: DEF },
    })
    await wrapper.find('[data-test="preview-trigger"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="preview-diagnostics"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('LIST_ITEM_RULE_NO_MATCH')
  })

  it('api 抛错 → 显示错误信息，不渲染行', async () => {
    vi.spyOn(visualSessionApi, 'previewList').mockRejectedValue(
      new Error('session closed'),
    )
    const wrapper = mount(ListPreviewPanel, {
      props: { sessionId: 's1', definition: DEF },
    })
    await wrapper.find('[data-test="preview-trigger"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('session closed')
    expect(wrapper.findAll('[data-test="preview-row-title"]').length).toBe(0)
  })
})