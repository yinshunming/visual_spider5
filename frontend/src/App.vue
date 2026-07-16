<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'

const REMOTE_W = 1280
const REMOTE_H = 720

const url = ref('')
const statusUrl = ref('')
const connected = ref(false)
const frameSrc = ref('')

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
  send({ type: 'click', x: Math.round(e.clientX - rect.left), y: Math.round(e.clientY - rect.top) })
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

onMounted(connect)
onUnmounted(() => ws?.close())
</script>

<template>
  <div class="app">
    <div class="bar">
      <input v-model="url" placeholder="https:// 或 fixture URL" @keyup.enter="navigate" />
      <button @click="navigate">导航</button>
      <span class="status">{{ connected ? '已连接' : '断开' }} | {{ statusUrl }}</span>
    </div>
    <div class="stage" tabindex="0" @keydown="onKeydown">
      <img id="remote" :src="frameSrc" @click="onClick" @wheel="onWheel" alt="remote" />
    </div>
  </div>
</template>

<style>
body { margin: 0; font-family: sans-serif; }
.bar { display: flex; gap: 8px; padding: 8px; align-items: center; }
.bar input { flex: 1; }
.status { color: #555; font-size: 0.9em; }
.stage { padding: 8px; outline: none; }
#remote {
  width: 100%;
  max-width: 1280px;
  aspect-ratio: 16 / 9;
  border: 1px solid #ccc;
  display: block;
  background: #eee;
}
</style>
