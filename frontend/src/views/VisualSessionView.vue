<script setup lang="ts">
/**
 * M2-5 #21：VisualSessionView 主页面。
 *
 * - 通过 REST 打开/关闭会话（visualSessionApi）；
 * - 通过 WebSocket 收发二进制帧 + JSON 状态；
 * - 浏览器原生 WebSocket 不支持自定义 header，CSRF 用 query `?csrf=<token>`；
 * - 选择/浏览模式由 mode command 切换。
 */
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { visualSessionApi } from '../api/visualSession'
import { http, ApiError } from '../http'
import { WS_SCHEMA_VERSION, frame, isValidFrame } from '../contracts/wsProtocol'
import type { LifecycleState } from '../contracts/visualSession'

const props = defineProps<{ taskId: number }>()

const sessionId = ref<string>('')
const lifecycle = ref<LifecycleState>('CLOSED')
const lastFrameUrl = ref<string | null>(null)
const errorMessage = ref<string>('')
const mode = ref<'BROWSE' | 'SELECT'>('BROWSE')

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

onMounted(openSession)
onBeforeUnmount(closeSession)
</script>

<template>
  <div class="vs-view">
    <header class="vs-header">
      <h2>配置会话 · taskId {{ props.taskId }}</h2>
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
        <FieldEditorPanel :task-id="props.taskId" />
        <SelectorLiveFeedback :session-id="sessionId" />
        <PreviewPanel :session-id="sessionId" />
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