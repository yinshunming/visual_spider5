<script setup lang="ts">
/**
 * ListInferPanel 子组件（M4-6 #36 / spec §D3）。
 *
 * <p>SELECT 模式下点代表项 → 调 {@code visualSessionApi.infer} 拿推断结果 → 渲染
 * score / matchCount / components / ancestorPath / alternatives → 提供 ancestor 调整
 * 与 alternatives 切换。选中时 emit {@code update:listItemRule} 由父组件写入 buffer。
 *
 * <p>坐标由用户点击 RemoteBrowserFrame 触发；当前简化版本用一个固定坐标输入
 * （点代表项前用户先点 frame，外部调用 {@code inferWith(x, y)} 或 panel 内 click 按钮
 * 触发同一坐标占位）。后续接 frame click 时直接传入 mouse event coords。
 */
import { ref, computed } from 'vue'
import { visualSessionApi } from '../../api/visualSession'
import type {
  InferRequest,
  InferResponse,
  ListItemRule,
  TaskDefinition,
} from '../../contracts/visualSession'

const props = defineProps<{
  sessionId: string
  definition: TaskDefinition
}>()

const emit = defineEmits<{
  (e: 'update:listItemRule', rule: ListItemRule): void
}>()

const PLACEHOLDER_X = 640
const PLACEHOLDER_Y = 360

const inferRequest = ref<InferRequest>({
  x: PLACEHOLDER_X,
  y: PLACEHOLDER_Y,
  clientWidth: 1280,
  clientHeight: 720,
})

const result = ref<InferResponse | null>(null)
const loading = ref(false)
const errorMsg = ref<string | null>(null)

const localRule = computed<ListItemRule>(() => {
  return (
    props.definition.listItemRule ?? {
      selector: '',
      selectorType: 'CSS' as const,
    }
  )
})

async function runInfer(): Promise<void> {
  loading.value = true
  errorMsg.value = null
  try {
    result.value = await visualSessionApi.infer(props.sessionId, inferRequest.value)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function applyRule(rule: ListItemRule): void {
  emit('update:listItemRule', rule)
}

function shiftAncestor(depthIdx: number): void {
  const r = result.value
  if (r === null || r.ancestorPath.length === 0) {
    return
  }
  // depthIdx=0 → 最近一级（点击元素的父级）；1 → 上溯两级；…
  // 按钮「上溯一级」传 depthIdx=0（closest）。
  const idx = Math.min(Math.max(0, depthIdx), r.ancestorPath.length - 1)
  const hop = r.ancestorPath[idx]
  applyRule({ selector: hop.tagAndClass.split('.').join('.'), selectorType: 'CSS' })
}

function onSelectorInput(e: Event): void {
  const target = e.target as HTMLInputElement
  applyRule({ ...localRule.value, selector: target.value })
}

function onSelectorTypeChange(e: Event): void {
  const target = e.target as HTMLSelectElement
  applyRule({
    ...localRule.value,
    selectorType: target.value as ListItemRule['selectorType'],
  })
}
</script>

<template>
  <section class="list-infer">
    <header>
      <h3>候选列表项推断</h3>
      <button
        data-test="infer-trigger"
        :disabled="loading || !sessionId"
        @click="runInfer"
      >
        {{ loading ? '推断中…' : '点代表项 → 推断' }}
      </button>
    </header>

    <p class="list-infer__hint">
      视口坐标（占位）：x={{ inferRequest.x }} · y={{ inferRequest.y }} ·
      clientWidth={{ inferRequest.clientWidth }} · clientHeight={{ inferRequest.clientHeight }}
    </p>

    <label>
      selector
      <input
        data-test="list-item-selector"
        type="text"
        :value="localRule.selector"
        @input="onSelectorInput"
      />
    </label>
    <label>
      selectorType
      <select
        :value="localRule.selectorType ?? 'CSS'"
        @change="onSelectorTypeChange"
      >
        <option value="CSS">CSS</option>
        <option value="XPATH">XPATH</option>
      </select>
    </label>

    <p v-if="errorMsg" class="list-infer__err">错误：{{ errorMsg }}</p>

    <div v-if="result" class="list-infer__result">
      <p v-if="result.lowConfidence" data-test="infer-low-confidence" class="list-infer__warn">
        未命中：当前页面无重复结构 / 全部候选 score &lt; 0.6
      </p>

      <dl v-else>
        <div>
          <dt>命中 {{ result.matchCount }}</dt>
          <dd data-test="infer-match-count">{{ result.matchCount }}</dd>
        </div>
        <div>
          <dt>score {{ result.score.toFixed(2) }}</dt>
          <dd data-test="infer-score">{{ result.score.toFixed(2) }}</dd>
        </div>
        <div>
          <dt>组件</dt>
          <dd>
            <ul class="list-infer__components">
              <li v-for="c in result.components" :key="c.name">
                {{ c.name }}: raw={{ c.raw.toFixed(2) }} weighted={{ c.weighted.toFixed(2) }}
                <span v-if="c.note">（{{ c.note }}）</span>
              </li>
            </ul>
          </dd>
        </div>
      </dl>

      <section v-if="!result.lowConfidence && result.ancestorPath.length > 1">
        <h4>上溯调整</h4>
        <button data-test="ancestor-up" type="button" @click="shiftAncestor(0)">
          上溯一级
        </button>
        <ol>
          <li v-for="hop in result.ancestorPath" :key="hop.depth">
            depth={{ hop.depth }} · {{ hop.tagAndClass }}
          </li>
        </ol>
      </section>

      <section v-if="!result.lowConfidence && result.alternatives.length > 1">
        <h4>并列候选</h4>
        <ul>
          <li v-for="alt in result.alternatives" :key="alt">
            <button
              data-test="infer-alternative"
              type="button"
              @click="applyRule({ selector: alt, selectorType: 'CSS' })"
            >
              {{ alt }}
            </button>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>

<style scoped>
.list-infer {
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
  padding: 0.5rem 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  justify-content: space-between;
}
header h3 {
  margin: 0;
  font-size: 1rem;
}
button {
  padding: 0.25rem 0.6rem;
  font-size: 0.85rem;
  border: 1px solid var(--color-border, #d0d7de);
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
}
button:disabled { opacity: 0.5; cursor: not-allowed; }
.list-infer__hint {
  margin: 0;
  font-size: 0.75rem;
  color: var(--color-muted, #6b7280);
}
dl {
  display: grid;
  grid-template-columns: 5rem 1fr;
  gap: 0.25rem 0.75rem;
  margin: 0.25rem 0 0;
}
dt {
  font-size: 0.7rem;
  text-transform: uppercase;
  color: var(--color-muted, #6b7280);
}
dd {
  margin: 0;
  font-size: 0.9rem;
}
.list-infer__components {
  margin: 0;
  padding-left: 1rem;
  font-size: 0.85rem;
}
.list-infer__err {
  color: var(--color-error, #b91c1c);
  font-size: 0.85rem;
}
.list-infer__warn {
  color: #b45309;
  font-size: 0.85rem;
}
</style>