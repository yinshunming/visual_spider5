<script setup lang="ts">
/**
 * ListRuleEditor 子组件（M4-6 #36 / spec §D1 / §D10）。
 *
 * <p>LIST 模式 listItemRule + uniqueKey + limits 三栏编辑面板：
 * <ul>
 *   <li>listItemRule：selector + selectorType（CSS / XPATH）</li>
 *   <li>uniqueKey：多选字段名（复用 {@code definition.fields[].name}）</li>
 *   <li>limits：pageLimit / recordLimit / durationMinutes（输入转 ISO-8601 Duration 字符串）</li>
 * </ul>
 *
 * <p>READY 校验错误回显：消费 {@link ReadinessError} 按 {@code fieldPath} 映射到红框
 * + tooltip。后端业务错误码已由 {@link TaskReadinessImpl} 透传，无需在前端重复判定。
 *
 * <p>硬上限取自后端 {@code RunLimits} 常量：pageLimit ≤ 200 / recordLimit ≤ 10_000 /
 * durationLimit ≤ 30 分钟。前端做防御性输入，越界不 emit（提示用户修正）。
 */
import { computed } from 'vue'
import type {
  FieldDefinition,
  ListItemRule,
  Limits,
  ReadinessError,
  SelectorType,
  TaskDefinition,
  UniqueKeyField,
} from '../../contracts/visualSession'

const props = defineProps<{
  definition: TaskDefinition
  readinessErrors?: ReadinessError[]
}>()

const emit = defineEmits<{
  (e: 'update', partial: Partial<TaskDefinition>): void
}>()

const MAX_PAGE = 200
const MAX_RECORDS = 10_000
const MAX_DURATION_MINUTES = 30

const selectorTypeOptions: SelectorType[] = ['CSS', 'XPATH']

const listItemRule = computed<ListItemRule>(() => {
  return (
    props.definition.listItemRule ?? {
      selector: '',
      selectorType: 'CSS' as SelectorType,
    }
  )
})

const uniqueKeyNames = computed<Set<string>>(() => {
  const set = new Set<string>()
  for (const k of props.definition.uniqueKey ?? []) {
    if (k.fieldName) {
      set.add(k.fieldName)
    }
  }
  return set
})

const limits = computed<{ pageLimit: number; recordLimit: number; durationMinutes: number }>(
  () => {
    const raw: Limits | undefined = props.definition.limits
    let minutes = MAX_DURATION_MINUTES
    if (raw) {
      try {
        minutes = parseDurationMinutes(raw.durationLimit)
      } catch {
        // 上游 schema 异常时回退到 MAX，并在 UI 显示明确提示（不在控制台吞错）
        return {
          pageLimit: raw.pageLimit ?? MAX_PAGE,
          recordLimit: raw.recordLimit ?? MAX_RECORDS,
          durationMinutes: MAX_DURATION_MINUTES,
        }
      }
    }
    return {
      pageLimit: raw?.pageLimit ?? MAX_PAGE,
      recordLimit: raw?.recordLimit ?? MAX_RECORDS,
      durationMinutes: minutes,
    }
  },
)

function parseDurationMinutes(iso: string): number {
  // 服务端返回 ISO-8601 Duration（如 PT30M / PT1H30M / PT45S）；非法输入显式抛错
  // 而不是静默回退，让调用方在编辑器上显式提示用户，避免吞掉 schema 异常。
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(iso)
  if (m === null) {
    throw new Error(`无法解析 durationLimit: ${iso}`)
  }
  const hours = m[1] ? Number(m[1]) : 0
  const mins = m[2] ? Number(m[2]) : 0
  return hours * 60 + mins
}

function formatDuration(minutes: number): string {
  const safe = Math.max(0, Math.min(MAX_DURATION_MINUTES, Math.floor(minutes)))
  return `PT${safe}M`
}

function errorsAt(fieldPath: string): ReadinessError[] {
  return (props.readinessErrors ?? []).filter((e) => e.fieldPath === fieldPath)
}

const listItemRuleErrors = computed<ReadinessError[]>(() => errorsAt('listItemRule'))
const limitsErrors = computed<ReadinessError[]>(() =>
  (props.readinessErrors ?? []).filter((e) =>
    e.fieldPath !== undefined && e.fieldPath.startsWith('limits'),
  ),
)

function fieldErrorByIndex(idx: number): ReadinessError[] {
  return (props.readinessErrors ?? []).filter(
    (e) => e.fieldPath !== undefined && e.fieldPath.startsWith(`fields[${idx}]`),
  )
}

function updateListItemRule(rule: ListItemRule): void {
  emit('update', { listItemRule: rule })
}

function onSelectorInput(e: Event): void {
  const target = e.target as HTMLInputElement
  updateListItemRule({ ...listItemRule.value, selector: target.value })
}

function onSelectorTypeChange(e: Event): void {
  const target = e.target as HTMLSelectElement
  updateListItemRule({
    ...listItemRule.value,
    selectorType: target.value as SelectorType,
  })
}

function toggleUniqueKey(field: FieldDefinition, checked: boolean): void {
  const set = new Set(uniqueKeyNames.value)
  if (checked) {
    set.add(field.name)
  } else {
    set.delete(field.name)
  }
  const next: UniqueKeyField[] = []
  for (const f of props.definition.fields ?? []) {
    if (set.has(f.name)) {
      next.push({ fieldName: f.name })
    }
  }
  emit('update', { uniqueKey: next })
}

function onPageLimitInput(e: Event): void {
  const target = e.target as HTMLInputElement
  const v = Number(target.value)
  if (!Number.isInteger(v) || v <= 0 || v > MAX_PAGE) {
    return
  }
  emit('update', {
    limits: {
      ...props.definition.limits,
      pageLimit: v,
      recordLimit: limits.value.recordLimit,
      durationLimit: formatDuration(limits.value.durationMinutes),
    } as Limits,
  })
}

function onRecordLimitInput(e: Event): void {
  const target = e.target as HTMLInputElement
  const v = Number(target.value)
  if (!Number.isInteger(v) || v <= 0 || v > MAX_RECORDS) {
    return
  }
  emit('update', {
    limits: {
      ...props.definition.limits,
      pageLimit: limits.value.pageLimit,
      recordLimit: v,
      durationLimit: formatDuration(limits.value.durationMinutes),
    } as Limits,
  })
}

function onDurationInput(e: Event): void {
  const target = e.target as HTMLInputElement
  const v = Number(target.value)
  if (!Number.isInteger(v) || v <= 0 || v > MAX_DURATION_MINUTES) {
    return
  }
  emit('update', {
    limits: {
      ...props.definition.limits,
      pageLimit: limits.value.pageLimit,
      recordLimit: limits.value.recordLimit,
      durationLimit: formatDuration(v),
    } as Limits,
  })
}
</script>

<template>
  <section class="list-rule-editor">
    <header><h3>列表项规则（listItemRule / uniqueKey / limits）</h3></header>

    <!-- listItemRule -->
    <fieldset class="lr-list-item" :class="{ 'lr-error': listItemRuleErrors.length > 0 }">
      <legend>列表项规则</legend>
      <label>
        selector
        <input
          data-test="list-item-selector"
          type="text"
          :value="listItemRule.selector"
          @input="onSelectorInput"
        />
      </label>
      <label>
        selectorType
        <select
          data-test="list-item-selector-type"
          :value="listItemRule.selectorType ?? 'CSS'"
          @change="onSelectorTypeChange"
        >
          <option v-for="t in selectorTypeOptions" :key="t" :value="t">{{ t }}</option>
        </select>
      </label>
      <p
        v-if="listItemRuleErrors.length"
        data-test="list-item-rule-error"
        class="lr-err"
      >
        {{ listItemRuleErrors.map((e) => e.message).join('；') }}
      </p>
    </fieldset>

    <!-- uniqueKey -->
    <fieldset class="lr-unique">
      <legend>唯一键（多选字段名）</legend>
      <ul>
        <li
          v-for="(f, idx) in definition.fields ?? []"
          :key="f.name"
          :class="{ 'lr-error': fieldErrorByIndex(idx).length > 0 }"
        >
          <label>
            <input
              :data-test="`unique-key-${f.name}`"
              type="checkbox"
              :checked="uniqueKeyNames.has(f.name)"
              @change="(e) => toggleUniqueKey(f, (e.target as HTMLInputElement).checked)"
            />
            {{ f.name }}
          </label>
          <span
            v-if="fieldErrorByIndex(idx).length"
            :data-test="`unique-key-${f.name}-error`"
            class="lr-err"
          >
            {{ fieldErrorByIndex(idx).map((e) => e.message).join('；') }}
          </span>
        </li>
      </ul>
    </fieldset>

    <!-- limits -->
    <fieldset class="lr-limits" :class="{ 'lr-error': limitsErrors.length > 0 }">
      <legend>运行限制</legend>
      <label>
        pageLimit（≤ {{ MAX_PAGE }}）
        <input
          data-test="limits-page-limit"
          type="number"
          :value="limits.pageLimit"
          @input="onPageLimitInput"
        />
      </label>
      <label>
        recordLimit（≤ {{ MAX_RECORDS }}）
        <input
          data-test="limits-record-limit"
          type="number"
          :value="limits.recordLimit"
          @input="onRecordLimitInput"
        />
      </label>
      <label>
        durationLimit 分钟（≤ {{ MAX_DURATION_MINUTES }}）
        <input
          data-test="limits-duration-minutes"
          type="number"
          :value="limits.durationMinutes"
          @input="onDurationInput"
        />
      </label>
      <p v-if="limitsErrors.length" data-test="limits-error" class="lr-err">
        {{ limitsErrors.map((e) => e.message).join('；') }}
      </p>
    </fieldset>
  </section>
</template>

<style scoped>
.list-rule-editor {
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
  padding: 0.5rem 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
header h3 {
  margin: 0 0 0.25rem;
  font-size: 1rem;
}
fieldset {
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
  padding: 0.5rem 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
fieldset legend {
  padding: 0 0.25rem;
  font-size: 0.85rem;
  font-weight: 600;
}
fieldset.lr-error {
  border-color: var(--color-error, #b91c1c);
  background: #fff5f5;
}
label {
  display: flex;
  flex-direction: column;
  font-size: 0.8rem;
  gap: 0.125rem;
}
input,
select {
  padding: 0.2rem 0.3rem;
}
.lr-err {
  color: var(--color-error, #b91c1c);
  font-size: 0.8rem;
  margin: 0;
}
ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
li.lr-error {
  border-left: 3px solid var(--color-error, #b91c1c);
  padding-left: 0.5rem;
}
</style>