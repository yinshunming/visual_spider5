<script setup lang="ts">
/**
 * VisualSessionView 主页面。
 *
 * - 通过 REST 打开/关闭会话（visualSessionApi）；
 * - 通过 WebSocket 收发二进制帧 + JSON 状态；
 * - 浏览器原生 WebSocket 不支持自定义 header，CSRF 用 query `?csrf=<token>`；
 * - 选择/浏览模式由 mode command 切换；
 * - M4-6 #36：fetch task draft 拿 mode + definition；LIST 模式挂 ListInferPanel；
 *   透传 mode 给 FieldEditorPanel / PreviewPanel。
 */
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { visualSessionApi } from '../api/visualSession'
import { tasksApi } from '../api/tasks'
import { ApiError } from '../http'
import { WS_SCHEMA_VERSION, frame, isValidFrame } from '../contracts/wsProtocol'
import RemoteBrowserFrame from '../components/visual-session/RemoteBrowserFrame.vue'
import AddressBar from '../components/visual-session/AddressBar.vue'
import ModeSwitcher from '../components/visual-session/ModeSwitcher.vue'
import LifecycleBadge from '../components/visual-session/LifecycleBadge.vue'
import FieldEditorPanel from '../components/visual-session/FieldEditorPanel.vue'
import SelectorLiveFeedback from '../components/visual-session/SelectorLiveFeedback.vue'
import PreviewPanel from '../components/visual-session/PreviewPanel.vue'
import ListInferPanel from '../components/visual-session/ListInferPanel.vue'
import type {
  LifecycleState,
  ReadinessError,
  SelectorType,
  TaskDefinition,
  TaskMode,
} from '../contracts/visualSession'
import type { TaskDraft } from '../contracts/task'

const props = defineProps<{ taskId: number }>()

const sessionId = ref<string>('')
const lifecycle = ref<LifecycleState>('CLOSED')
const lastFrameUrl = ref<string | null>(null)
const errorMessage = ref<string>('')
const mode = ref<'BROWSE' | 'SELECT'>('BROWSE')

// M4-6 #36：任务模式 + 定义
const taskDraft = ref<TaskDraft | null>(null)
const taskMode = computed<TaskMode>(() => taskDraft.value?.mode ?? 'SINGLE_PAGE')
const taskDefinition = computed<TaskDefinition | null>(
  () => taskDraft.value?.definition ?? null,
)
// M4-6 #36：保存草稿后端返回的 READY 校验错误（含 MULTIPLE_MATCH / LIST_ITEM_RULE_NO_MATCH 等）。
const readinessErrors = ref<ReadinessError[]>([])

let socket: WebSocket | null = null

async function openSession(): Promise<void> {
  try {
    const session = await visualSessionApi.open(props.taskId)
    sessionId.value = session.sessionId
    lifecycle.value = session.lifecycle
    await connectSocket(session.sessionId)
  } catch (e) {
    errorMessage.value = formatError(e)
  }
}

async function loadTaskDraft(): Promise<void> {
  try {
    taskDraft.value = await tasksApi.get(props.taskId)
  } catch (e) {
    // 任务不可读不阻塞会话：UI 走单页默认渲染
    if (e instanceof ApiError) {
      errorMessage.value = `${e.status} ${e.message}`
    }
  }
}

async function connectSocket(id: string): Promise<void> {
  const token = readCookie('XSRF-TOKEN') ?? ''
  const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
  const url = `${protocol}://${location.host}/ws/visual-sessions/${encodeURIComponent(id)}?csrf=${encodeURIComponent(token)}`
  socket = new WebSocket(url)
  socket.binaryType = 'arraybuffer'
  socket.onmessage = (event) => {
    if (typeof event.data === 'string') {
      try {
        const payload = JSON.parse(event.data)
        if (isValidFrame(payload) && payload.type === 'status') {
          lastFrameUrl.value = payload.url ?? null
        }
      } catch (e) {
        console.warn('ws payload parse failed', e)
      }
    } else if (event.data instanceof ArrayBuffer) {
      const blob = new Blob([event.data], { type: 'image/jpeg' })
      const next = URL.createObjectURL(blob)
      const prev = lastFrameUrl.value
      lastFrameUrl.value = next
      // spec §D3 帧通道只保留最新帧：立即回收上一帧的 object URL，避免内存累积。
      if (prev && prev.startsWith('blob:')) {
        URL.revokeObjectURL(prev)
      }
    }
  }
  socket.onclose = () => {
    lifecycle.value = 'CLOSED'
  }
}

async function closeSession(): Promise<void> {
  if (!sessionId.value) {
    return
  }
  try {
    await visualSessionApi.close(sessionId.value)
  } finally {
    socket?.close()
    socket = null
    sessionId.value = ''
    lifecycle.value = 'CLOSED'
    releaseFrame()
  }
}

function releaseFrame(): void {
  const prev = lastFrameUrl.value
  if (prev && prev.startsWith('blob:')) {
    URL.revokeObjectURL(prev)
  }
  lastFrameUrl.value = null
}

async function heartbeat(): Promise<void> {
  if (!sessionId.value) {
    return
  }
  await visualSessionApi.heartbeat(sessionId.value)
}

function switchMode(next: 'BROWSE' | 'SELECT'): void {
  mode.value = next
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    return
  }
  socket.send(
    JSON.stringify(
      frame('switchMode', {
        sessionId: sessionId.value,
        sequence: nextSequence(),
        clientWidth: 1280,
        clientHeight: 720,
        mode: next,
      }),
    ),
  )
}

let sequenceCounter = 0
function nextSequence(): number {
  sequenceCounter += 1
  return sequenceCounter
}

function readCookie(name: string): string | null {
  const m = document.cookie.split('; ')
  for (const pair of m) {
    const [k, v] = pair.split('=')
    if (k === name) {
      return decodeURIComponent(v ?? '')
    }
  }
  return null
}

function formatError(e: unknown): string {
  if (e instanceof ApiError) {
    return `${e.status} ${e.body?.code ?? e.body?.error ?? e.message}`
  }
  return e instanceof Error ? e.message : String(e)
}

const showLifecycle = computed(() => lifecycle.value)

// M4-6 #36：listItemRule 推断后回流到本地 draft；FieldEditorPanel 通过 definition prop 拿最新值。
function onListItemRuleUpdate(rule: { selector: string; selectorType?: SelectorType }): void {
  if (taskDraft.value === null) {
    return
  }
  taskDraft.value = {
    ...taskDraft.value,
    definition: {
      ...taskDraft.value.definition,
      listItemRule: { selector: rule.selector, selectorType: rule.selectorType },
    },
  }
}

// M4-6 #36：FieldEditorPanel.save 失败 → 接收 readiness 错误并显示在 ListRuleEditor。
function onFieldEditorError(errors: ReadinessError[]): void {
  readinessErrors.value = errors
}

onMounted(async () => {
  await loadTaskDraft()
  await openSession()
})
onBeforeUnmount(closeSession)
</script>

<template>
  <div class="vs-view">
    <header class="vs-header">
      <h2>配置会话 · taskId {{ props.taskId }}（{{ taskMode }}）</h2>
      <LifecycleBadge :lifecycle="lifecycle" />
      <button @click="heartbeat">心跳</button>
      <button @click="closeSession">关闭</button>
      <span v-if="errorMessage" class="vs-error">{{ errorMessage }}</span>
    </header>
    <main class="vs-main">
      <RemoteBrowserFrame :frame-url="lastFrameUrl" />
      <aside class="vs-aside">
        <AddressBar :url="lastFrameUrl" />
        <ModeSwitcher :mode="mode" @switch="switchMode" />
        <template v-if="taskDefinition">
          <FieldEditorPanel
            :session-id="sessionId"
            :definition="taskDefinition"
            :readiness-errors="readinessErrors"
            @error="onFieldEditorError"
          />
          <ListInferPanel
            v-if="taskMode === 'LIST' && sessionId"
            :session-id="sessionId"
            :definition="taskDefinition"
            @update:list-item-rule="onListItemRuleUpdate"
          />
          <SelectorLiveFeedback :session-id="sessionId" />
          <PreviewPanel :session-id="sessionId" :definition="taskDefinition" :mode="taskMode" />
        </template>
      </aside>
    </main>
    <footer class="vs-footer">
      schemaVersion {{ WS_SCHEMA_VERSION }} · lifecycle {{ showLifecycle }}
    </footer>
  </div>
</template>

<style scoped>
.vs-view {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}
.vs-header {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--color-border, #d0d7de);
}
.vs-main {
  display: flex;
  flex: 1 1 auto;
}
.vs-aside {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  width: 320px;
  padding: 1rem;
  border-left: 1px solid var(--color-border, #d0d7de);
}
.vs-error {
  color: var(--color-error, #b00020);
}
.vs-footer {
  padding: 0.5rem 1rem;
  border-top: 1px solid var(--color-border, #d0d7de);
  color: var(--color-text-secondary, #57606a);
}
</style>