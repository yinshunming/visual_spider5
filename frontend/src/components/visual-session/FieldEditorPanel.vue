<script setup lang="ts">
/**
 * 字段编辑面板（M2-5 #21 acceptance §D12；M4-6 #36 扩 listItemRule / uniqueKey / limits）。
 *
 * <p>字段列表可在 UI 内编辑；LIST 模式额外挂 {@link ListRuleEditor} 三栏。
 * 点击"保存"通过 visualSessionApi.patchBuffer 把整段 TaskDefinition 推送到后端
 * EditingBuffer（spec §D10 防抖自动保存）。
 */
import { reactive, ref } from 'vue'
import { visualSessionApi } from '../../api/visualSession'
import { ApiError } from '../../http'
import ListRuleEditor from './ListRuleEditor.vue'
import type {
  FieldDefinition,
  ReadinessError,
  ResultType,
  TaskDefinition,
} from '../../contracts/visualSession'

const props = defineProps<{
  sessionId: string
  definition: TaskDefinition
  /** M4-6 #36：可选，LIST 模式时透传给 ListRuleEditor 用于错误回显。 */
  readinessErrors?: ReadinessError[]
}>()

const fields = reactive<FieldDefinition[]>(
  props.definition.fields.map((f) => ({ ...f })),
)

// M4-6 #36 LIST 模式额外字段；仅在保存时合并进 payload。
const listExtras = reactive<{
  listItemRule?: TaskDefinition['listItemRule']
  uniqueKey?: TaskDefinition['uniqueKey']
  limits?: TaskDefinition['limits']
}>({
  listItemRule: props.definition.listItemRule,
  uniqueKey: props.definition.uniqueKey,
  limits: props.definition.limits,
})

const status = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
const errorMessage = ref('')

const emit = defineEmits<{
  (e: 'update', partial: Partial<TaskDefinition>): void
  (e: 'error', readinessErrors: ReadinessError[]): void
}>()

function addField(): void {
  fields.push({
    name: '',
    source: 'VISIBLE_TEXT',
    selector: '',
    attributeName: '',
    resultType: 'TEXT',
    trim: 'TRIM',
    regex: '',
    required: false,
  })
}

function removeField(index: number): void {
  fields.splice(index, 1)
}

function onListExtrasUpdate(partial: Partial<TaskDefinition>): void {
  if (partial.listItemRule !== undefined) {
    listExtras.listItemRule = partial.listItemRule
  }
  if (partial.uniqueKey !== undefined) {
    listExtras.uniqueKey = partial.uniqueKey
  }
  if (partial.limits !== undefined) {
    listExtras.limits = partial.limits
  }
}

async function save(): Promise<void> {
  status.value = 'saving'
  errorMessage.value = ''
  const payload: TaskDefinition = {
    schemaVersion: props.definition.schemaVersion,
    mode: props.definition.mode,
    startUrl: props.definition.startUrl,
    viewport: props.definition.viewport,
    fields: fields.map((f) => ({ ...f })),
  }
  if (props.definition.mode === 'LIST') {
    // 仅写入已显式编辑过的字段；undefined 会覆盖已有快照为 null，不安全。
    if (listExtras.listItemRule !== undefined) {
      payload.listItemRule = listExtras.listItemRule
    }
    if (listExtras.uniqueKey !== undefined) {
      payload.uniqueKey = listExtras.uniqueKey
    }
    if (listExtras.limits !== undefined) {
      payload.limits = listExtras.limits
    }
  }
  try {
    await visualSessionApi.patchBuffer(props.sessionId, payload)
    status.value = 'saved'
    // 成功后清掉 readiness 错误
    emit('error', [])
  } catch (e) {
    status.value = 'error'
    errorMessage.value = e instanceof Error ? e.message : String(e)
    // 透传到父 view，由 ListRuleEditor 按 fieldPath 红框回显
    const businessErrors = extractReadinessErrors(e)
    emit('error', businessErrors)
  }
}

function extractReadinessErrors(e: unknown): ReadinessError[] {
  // M4-6 #36：后端 409 业务错误含 BusinessError[]；按字段语义提取。
  // 已知 schema：code 在 BusinessErrorCode 枚举里且含 ReadinessError.code 子集。
  if (e instanceof ApiError && e.body && Array.isArray((e.body as { errors?: unknown[] }).errors)) {
    const errors = (e.body as { errors: Array<{ code?: string; message?: string; fieldPath?: string }> }).errors
    return errors
      .filter((err) => typeof err.code === 'string')
      .map((err) => ({
        code: err.code as ReadinessError['code'],
        message: typeof err.message === 'string' ? err.message : '',
        fieldPath: typeof err.fieldPath === 'string' ? err.fieldPath : undefined,
      }))
  }
  return []
}

const SOURCES: FieldDefinition['source'][] = [
  'VISIBLE_TEXT',
  'ATTRIBUTE',
  'LINK_URL',
  'IMAGE_URL',
  'PAGE_URL',
]
const RESULT_TYPES: ResultType[] = ['TEXT', 'NUMBER', 'URL']
</script>

<template>
  <section class="fields">
    <header>
      <h3>字段编辑</h3>
      <button @click="addField">+ 新增字段</button>
    </header>
    <ol>
      <li v-for="(f, index) in fields" :key="index" class="row">
        <label>
          名称
          <input v-model="f.name" type="text" placeholder="title" />
        </label>
        <label>
          来源
          <select v-model="f.source">
            <option v-for="s in SOURCES" :key="s" :value="s">{{ s }}</option>
          </select>
        </label>
        <label v-if="f.source !== 'PAGE_URL'">
          选择器
          <input v-model="f.selector" type="text" placeholder="h1 / .title" />
        </label>
        <label v-if="f.source === 'ATTRIBUTE' || f.source === 'LINK_URL' || f.source === 'IMAGE_URL'">
          属性名
          <input
            v-model="f.attributeName"
            type="text"
            :placeholder="f.source === 'ATTRIBUTE' ? 'href / data-id' : f.source === 'LINK_URL' ? 'href' : 'src'"
          />
        </label>
        <label>
          类型
          <select v-model="f.resultType">
            <option v-for="r in RESULT_TYPES" :key="r" :value="r">{{ r }}</option>
          </select>
        </label>
        <label>
          正则
          <input v-model="f.regex" type="text" placeholder="可选：捕获组 1 优先" />
        </label>
        <label class="check">
          <input v-model="f.required" type="checkbox" /> 必填
        </label>
        <button class="remove" @click="removeField(index)" type="button">×</button>
      </li>
    </ol>
    <ListRuleEditor
      v-if="props.definition.mode === 'LIST'"
      :definition="props.definition"
      :readiness-errors="props.readinessErrors ?? []"
      @update="onListExtrasUpdate"
    />
    <footer>
      <button :disabled="status === 'saving'" @click="save">
        {{ status === 'saving' ? '保存中…' : '保存到会话缓冲' }}
      </button>
      <span v-if="status === 'saved'" class="ok">已入防抖缓冲（5 秒内自动落库）</span>
      <span v-if="status === 'error'" class="err">错误：{{ errorMessage }}</span>
    </footer>
  </section>
</template>

<style scoped>
.fields {
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 4px;
  padding: 0.5rem 0.75rem;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
h3 {
  margin: 0;
  font-size: 1rem;
}
ol {
  list-style: none;
  margin: 0.5rem 0 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.row {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr 1fr 1fr auto auto;
  gap: 0.25rem;
  align-items: end;
  border-bottom: 1px dashed var(--color-border, #d0d7de);
  padding-bottom: 0.25rem;
}
label {
  display: flex;
  flex-direction: column;
  font-size: 0.8rem;
  gap: 0.125rem;
}
label.check {
  flex-direction: row;
  align-items: center;
  gap: 0.25rem;
}
input,
select {
  padding: 0.125rem;
}
.remove {
  align-self: end;
  background: transparent;
  border: 1px solid var(--color-border, #d0d7de);
  border-radius: 999px;
  width: 1.75rem;
  height: 1.75rem;
  cursor: pointer;
}
footer {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  margin-top: 0.5rem;
}
.ok {
  color: var(--color-ok, #137333);
  font-size: 0.85rem;
}
.err {
  color: var(--color-error, #b00020);
  font-size: 0.85rem;
}
</style>