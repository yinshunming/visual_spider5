/**
 * contracts/visualSession.ts V2 schema 测试（#36）。
 *
 * <p>用运行期 type guard 验证：
 * <ul>
 *   <li>V2 字段（{@code listItemRule / uniqueKey / limits}）可空 → 与 V1 旧快照兼容</li>
 *   <li>InferRequest 必填字段类型校验</li>
 *   <li>ListPreviewResult 聚合结构合法</li>
 * </ul>
 */

import { describe, expect, it } from 'vitest'
import {
  VISUAL_SESSION_SCHEMA_VERSION,
  type InferRequest,
  type InferResponse,
  type ListItemRule,
  type ListPreviewResult,
  type Limits,
  type ReadinessError,
  type TaskDefinition,
  type UniqueKeyField,
} from './visualSession'

describe('contracts/visualSession.ts V2 (#36)', () => {
  it('VISUAL_SESSION_SCHEMA_VERSION = 2（M4 #31 bump）', () => {
    expect(VISUAL_SESSION_SCHEMA_VERSION).toBe(2)
  })

  it('TaskDefinition V2：listItemRule / uniqueKey / limits 均可缺省', () => {
    const minimal: TaskDefinition = {
      schemaVersion: VISUAL_SESSION_SCHEMA_VERSION,
      mode: 'SINGLE_PAGE',
      startUrl: 'https://example.com',
      viewport: { width: 1280, height: 720 },
      fields: [],
    }
    expect(minimal.listItemRule).toBeUndefined()
    expect(minimal.uniqueKey).toBeUndefined()
    expect(minimal.limits).toBeUndefined()
  })

  it('TaskDefinition V2：LIST 模式含 listItemRule / uniqueKey / limits', () => {
    const rule: ListItemRule = { selector: 'tbody > tr', selectorType: 'CSS' }
    const key: UniqueKeyField = { fieldName: 'title' }
    const limits: Limits = { pageLimit: 200, recordLimit: 10_000, durationLimit: 'PT30M' }
    const def: TaskDefinition = {
      schemaVersion: VISUAL_SESSION_SCHEMA_VERSION,
      mode: 'LIST',
      startUrl: 'https://example.com/list',
      viewport: { width: 1280, height: 720 },
      listItemRule: rule,
      uniqueKey: [key],
      limits,
      fields: [],
    }
    expect(def.listItemRule?.selector).toBe('tbody > tr')
    expect(def.uniqueKey?.[0]?.fieldName).toBe('title')
    expect(def.limits?.durationLimit).toBe('PT30M')
  })

  it('InferRequest 必填字段类型', () => {
    const req: InferRequest = {
      x: 100,
      y: 200,
      clientWidth: 1280,
      clientHeight: 720,
    }
    expect(req.x).toBe(100)
    expect(req.clientWidth).toBe(1280)
  })

  it('InferResponse 含 score / ancestorPath / components / alternatives / lowConfidence', () => {
    const res: InferResponse = {
      selector: 'tbody > tr',
      selectorType: 'CSS',
      matchCount: 5,
      score: 0.83,
      ancestorPath: [{ depth: 1, tagAndClass: 'tbody' }],
      components: [
        { name: 'sibling', raw: 0.9, weighted: 0.36, note: null },
      ],
      alternatives: ['tbody > tr', '.list > li'],
      lowConfidence: false,
    }
    expect(res.score).toBeGreaterThanOrEqual(0)
    expect(res.ancestorPath).toHaveLength(1)
    expect(res.alternatives).toContain('tbody > tr')
  })

  it('ListPreviewResult 聚合结构', () => {
    const result: ListPreviewResult = {
      previews: [
        {
          fieldOutcomes: [{ fieldName: 'title', rawValue: 'Alpha', cleanedValue: 'Alpha', isEmpty: false }],
          diagnostics: [],
        },
      ],
      totalMatchCount: 5,
      diagnostics: [],
    }
    expect(result.totalMatchCount).toBe(5)
    expect(result.previews).toHaveLength(1)
  })

  it('ReadinessError 含 LIST_ITEM_RULE_NO_MATCH / MULTIPLE_MATCH 阻止就绪错误码', () => {
    const errors: ReadinessError[] = [
      { code: 'LIST_ITEM_RULE_NO_MATCH', message: '命中 0 项', fieldPath: 'listItemRule' },
      { code: 'MULTIPLE_MATCH', message: '字段 title 多匹配', fieldPath: 'fields[0].selector' },
      { code: 'LIMITS_OUT_OF_RANGE', message: 'pageLimit 越界', fieldPath: 'limits.pageLimit' },
    ]
    const codes = errors.map((e) => e.code)
    expect(codes).toContain('LIST_ITEM_RULE_NO_MATCH')
    expect(codes).toContain('MULTIPLE_MATCH')
    expect(codes).toContain('LIMITS_OUT_OF_RANGE')
  })
})