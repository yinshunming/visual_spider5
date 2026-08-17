/**
 * ListRuleEditor 子组件测试（#36 / spec §D1 / §D10）。
 *
 *  <p>覆盖：
 *  <ul>
 *    <li>三栏 UI：listItemRule + uniqueKey 多选 + limits 三输入</li>
 *    <li>selector / selectorType 变化 emit update</li>
 *    <li>uniqueKey toggle emit update（含多选）</li>
 *    <li>limits 输入越界提示（前端防御性，不依赖后端）</li>
 *    <li>readinessErrors 按 fieldPath 红框回显</li>
 *  </ul>
 */
import { describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import ListRuleEditor from './ListRuleEditor.vue'
import type {
  FieldDefinition,
  ListItemRule,
  Limits,
  ReadinessError,
  TaskDefinition,
  UniqueKeyField,
} from '../../contracts/visualSession'

const TITLE_FIELD: FieldDefinition = {
  name: 'title',
  source: 'VISIBLE_TEXT',
  selector: '.title',
  resultType: 'TEXT',
  trim: 'TRIM',
  required: true,
}

const DATE_FIELD: FieldDefinition = {
  name: 'date',
  source: 'VISIBLE_TEXT',
  selector: '.date',
  resultType: 'TEXT',
  trim: 'TRIM',
  required: false,
}

function baseDefinition(): TaskDefinition {
  return {
    schemaVersion: 2,
    mode: 'LIST',
    startUrl: 'https://example.com/list',
    viewport: { width: 1280, height: 720 },
    listItemRule: { selector: 'tbody > tr', selectorType: 'CSS' },
    uniqueKey: [{ fieldName: 'title' }],
    limits: { pageLimit: 200, recordLimit: 10_000, durationLimit: 'PT30M' },
    fields: [TITLE_FIELD, DATE_FIELD],
  }
}

function mountEditor(
  def: TaskDefinition = baseDefinition(),
  readinessErrors: ReadinessError[] = [],
): VueWrapper {
  return mount(ListRuleEditor, {
    props: { definition: def, readinessErrors },
  })
}

function lastUpdate(wrapper: VueWrapper): Record<string, unknown> {
  const events = wrapper.emitted('update') ?? []
  return events[events.length - 1]?.[0] as Record<string, unknown>
}

describe('ListRuleEditor.vue (#36)', () => {
  it('渲染三栏：listItemRule + uniqueKey + limits', () => {
    const wrapper = mountEditor()
    expect(wrapper.text()).toContain('列表项规则')
    expect(wrapper.text()).toContain('唯一键')
    expect(wrapper.text()).toContain('限制')
  })

  it('listItemRule selector 输入变化 → emit update（含新 selector）', async () => {
    const wrapper = mountEditor()
    const selectorInput = wrapper.find('input[data-test="list-item-selector"]')
    expect(selectorInput.exists()).toBe(true)
    await selectorInput.setValue('table > tbody > tr')
    const update = lastUpdate(wrapper) as { listItemRule?: ListItemRule }
    expect(update.listItemRule?.selector).toBe('table > tbody > tr')
  })

  it('listItemRule selectorType 切换 CSS <-> XPATH → emit update', async () => {
    const wrapper = mountEditor()
    const select = wrapper.find('select[data-test="list-item-selector-type"]')
    expect(select.exists()).toBe(true)
    await select.setValue('XPATH')
    const update = lastUpdate(wrapper) as { listItemRule?: ListItemRule }
    expect(update.listItemRule?.selectorType).toBe('XPATH')
  })

  it('uniqueKey 勾选/取消 → emit update（保留其它选中项）', async () => {
    const wrapper = mountEditor()
    const dateCheckbox = wrapper.find('input[data-test="unique-key-date"]')
    expect(dateCheckbox.exists()).toBe(true)
    await dateCheckbox.setValue(true)
    const update = lastUpdate(wrapper) as { uniqueKey?: UniqueKeyField[] }
    const names = update.uniqueKey?.map((k) => k.fieldName)
    expect(names).toContain('title')
    expect(names).toContain('date')
  })

  it('uniqueKey 取消已有勾选 → emit update（从列表移除）', async () => {
    const wrapper = mountEditor()
    const titleCheckbox = wrapper.find('input[data-test="unique-key-title"]')
    expect(titleCheckbox.exists()).toBe(true)
    expect((titleCheckbox.element as HTMLInputElement).checked).toBe(true)
    await titleCheckbox.setValue(false)
    const update = lastUpdate(wrapper) as { uniqueKey?: UniqueKeyField[] }
    const names = update.uniqueKey?.map((k) => k.fieldName)
    expect(names).not.toContain('title')
  })

  it('limits pageLimit 输入越界 → 提示错误，不 emit update', async () => {
    const wrapper = mountEditor()
    const input = wrapper.find('input[data-test="limits-page-limit"]')
    expect(input.exists()).toBe(true)
    await input.setValue('999')
    expect(wrapper.text()).toContain('pageLimit')
    // 输入超上限 200 不被 emit
    const events = wrapper.emitted('update') ?? []
    const lastUpdatePayload = events[events.length - 1]?.[0] as { limits?: Limits }
    expect(lastUpdatePayload?.limits?.pageLimit).not.toBe(999)
  })

  it('limits pageLimit 合法值 → emit update（含新 limits）', async () => {
    const wrapper = mountEditor()
    const input = wrapper.find('input[data-test="limits-page-limit"]')
    await input.setValue('150')
    const update = lastUpdate(wrapper) as { limits?: Limits }
    expect(update.limits?.pageLimit).toBe(150)
  })

  it('limits recordLimit 输入合法 → emit update', async () => {
    const wrapper = mountEditor()
    const input = wrapper.find('input[data-test="limits-record-limit"]')
    await input.setValue('8000')
    const update = lastUpdate(wrapper) as { limits?: Limits }
    expect(update.limits?.recordLimit).toBe(8000)
  })

  it('limits durationLimit 输入合法 → emit update', async () => {
    const wrapper = mountEditor()
    const input = wrapper.find('input[data-test="limits-duration-minutes"]')
    await input.setValue('25')
    const update = lastUpdate(wrapper) as { limits?: Limits }
    expect(update.limits?.durationLimit).toBe('PT25M')
  })

  it('readinessErrors 含 LIST_ITEM_RULE_NO_MATCH → listItemRule 区域红框', () => {
    const errors: ReadinessError[] = [
      { code: 'LIST_ITEM_RULE_NO_MATCH', message: 'listItemRule 命中 0 项', fieldPath: 'listItemRule' },
    ]
    const wrapper = mountEditor(baseDefinition(), errors)
    expect(wrapper.find('[data-test="list-item-rule-error"]').exists()).toBe(true)
  })

  it('readinessErrors 含 MULTIPLE_MATCH → fields[0].selector 红框', () => {
    const errors: ReadinessError[] = [
      { code: 'MULTIPLE_MATCH', message: 'title 多匹配', fieldPath: 'fields[0].selector' },
    ]
    const wrapper = mountEditor(baseDefinition(), errors)
    expect(wrapper.find('[data-test="unique-key-title-error"]').exists()).toBe(true)
  })

  it('readinessErrors 含 LIMITS_OUT_OF_RANGE → limits 区域红框', () => {
    const errors: ReadinessError[] = [
      { code: 'LIMITS_OUT_OF_RANGE', message: 'pageLimit 越界', fieldPath: 'limits.pageLimit' },
    ]
    const wrapper = mountEditor(baseDefinition(), errors)
    expect(wrapper.find('[data-test="limits-error"]').exists()).toBe(true)
  })
})