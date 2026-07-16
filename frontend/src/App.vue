<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'

const REMOTE_W = 1280
const REMOTE_H = 720

const url = ref('')
const statusUrl = ref('')
const connected = ref(false)
const frameSrc = ref('')
const mode = ref<'browse' | 'select'>('browse')
const selection = ref<any>(null)

let ws: WebSocket | null = null
let sessionId = ''
let sequence = 0

function clientSize() {
  const img = document.getElementById('remote') as HTMLImageElement | null
  return img
    ? { clientWidth: img.clientWidth, clientHeight: img.clientHeight }
    : { clientWidth: REMOTE_W, clientHeight: REMOTE_H }
}

function send(cmd: Record<string, unknown>) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return
  cmd.sessionId = sessionId
  cmd.sequence = ++sequence
  Object.assign(cmd, clientSize())
  ws.send(JSON.stringify(cmd))
}

function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  ws = new WebSocket(`${proto}://${location.host}/ws/visual`)
  ws.binaryType = 'arraybuffer'
  ws.onopen = () => { connected.value = true }
  ws.onclose = () => { connected.value = false }
  ws.onmessage = (ev: MessageEvent) => {
    if (typeof ev.data === 'string') {
      try {
        const msg = JSON.parse(ev.data)
        if (msg.sessionId) sessionId = msg.sessionId
        if (msg.url !== undefined) statusUrl.value = msg.url
        if (msg.selection !== undefined && msg.selection !== null) {
          selection.value = msg.selection
        }
      } catch {
        // 忽略非 JSON 文本
      }
    } else {
      if (frameSrc.value) URL.revokeObjectURL(frameSrc.value)
      frameSrc.value = URL.createObjectURL(new Blob([ev.data], { type: 'image/jpeg' }))
    }
  }
}

function onClick(e: MouseEvent) {
  const img = e.currentTarget as HTMLImageElement
  const rect = img.getBoundingClientRect()
  const x = Math.round(e.clientX - rect.left)
  const y = Math.round(e.clientY - rect.top)
  // 选择模式发 select（只检查 DOM，不触发原页面动作）；浏览模式发 click
  send({ type: mode.value === 'select' ? 'select' : 'click', x, y })
}

function onWheel(e: WheelEvent) {
  e.preventDefault()
  send({ type: 'wheel', deltaX: Math.round(e.deltaX), deltaY: Math.round(e.deltaY) })
}

function onKeydown(e: KeyboardEvent) {
  if (e.key.length === 1) send({ type: 'key', key: e.key })
}

function navigate() {
  if (url.value) send({ type: 'navigate', url: url.value })
}

function toggleMode() {
  mode.value = mode.value === 'browse' ? 'select' : 'browse'
  selection.value = null
}

// 高亮框：selection.boundingBox 是远程视口坐标，按百分比换算到 frame-wrap（与 img 同尺寸，自适应缩放）
const highlightStyle = computed(() => {
  if (!selection.value) return { display: 'none' }
  return {
    left: (selection.value.x / REMOTE_W * 100) + '%',
    top: (selection.value.y / REMOTE_H * 100) + '%',
    width: (selection.value.width / REMOTE_W * 100) + '%',
    height: (selection.value.height / REMOTE_H * 100) + '%',
  }
})

onMounted(connect)
onUnmounted(() => ws?.close())
</script>

<template>
  <div class="app">
    <div class="bar">
      <input v-model="url" placeholder="https:// 或 fixture URL" @keyup.enter="navigate" />
      <button @click="navigate">导航</button>
      <button @click="toggleMode">{{ mode === 'browse' ? '切换到选择模式' : '切换到浏览模式' }}</button>
      <span class="status">{{ connected ? '已连接' : '断开' }} | {{ mode }} | {{ statusUrl }}</span>
    </div>
    <div class="stage" tabindex="0" @keydown="onKeydown">
      <div class="frame-wrap">
        <img id="remote" :src="frameSrc" @click="onClick" @wheel="onWheel" alt="remote" />
        <div v-if="selection" class="highlight" :style="highlightStyle"></div>
      </div>
    </div>
    <div v-if="selection" class="selection-info">
      {{ selection.tagName }}<span v-if="selection.id"> #{{ selection.id }}</span><span v-if="selection.className"> .{{ selection.className }}</span>
      <span v-if="selection.text"> | {{ selection.text.substring(0, 60) }}</span>
    </div>
  </div>
</template>

<style>
body { margin: 0; font-family: sans-serif; }
.bar { display: flex; gap: 8px; padding: 8px; align-items: center; flex-wrap: wrap; }
.bar input { flex: 1; min-width: 200px; }
.status { color: #555; font-size: 0.9em; }
.stage { padding: 8px; outline: none; }
.frame-wrap { position: relative; display: inline-block; width: 100%; max-width: 1280px; }
#remote {
  width: 100%;
  max-width: 1280px;
  aspect-ratio: 16 / 9;
  border: 1px solid #ccc;
  display: block;
  background: #eee;
}
.highlight {
  position: absolute;
  border: 2px solid #ffd400;
  box-shadow: 0 0 0 9999px rgba(0, 0, 0, 0.25);
  pointer-events: none;
}
.selection-info { padding: 8px; font-size: 0.85em; color: #333; background: #f5f5f5; }
</style>
