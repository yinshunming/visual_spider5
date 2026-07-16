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
const validation = ref<any>(null)
const selectorText = ref('#input')
const selectorType = ref<'css' | 'xpath'>('css')

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
          validation.value = null
        }
        if (msg.validationResult !== undefined && msg.validationResult !== null) {
          validation.value = msg.validationResult
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
  validation.value = null
}

function validateSelector() {
  if (!selectorText.value) return
  send({
    type: 'validate',
    selector: selectorText.value,
    selectorType: selectorType.value,
  })
}

// 高亮数据源：validate 多元素优先；否则 select 单元素
const highlights = computed(() => {
  const list: { x: number; y: number; w: number; h: number }[] = []
  if (validation.value?.elements?.length) {
    for (const e of validation.value.elements) {
      list.push({ x: e.x, y: e.y, w: e.width, h: e.height })
    }
  } else if (selection.value) {
    list.push({
      x: selection.value.x,
      y: selection.value.y,
      w: selection.value.width,
      h: selection.value.height,
    })
  }
  return list
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
        <div
          v-for="(hl, i) in highlights"
          :key="i"
          class="highlight"
          :style="{
            left: (hl.x / REMOTE_W * 100) + '%',
            top: (hl.y / REMOTE_H * 100) + '%',
            width: (hl.w / REMOTE_W * 100) + '%',
            height: (hl.h / REMOTE_H * 100) + '%',
          }"
        ></div>
      </div>
    </div>
    <div v-if="selection" class="selection-info">
      <strong>{{ selection.tagName }}<span v-if="selection.id"> #{{ selection.id }}</span></strong>
      <div v-if="selection.cssCandidates?.length">
        <span class="label">CSS:</span> {{ selection.cssCandidates.join(', ') }}
      </div>
      <div v-if="selection.xpathCandidates?.length">
        <span class="label">XPath:</span> {{ selection.xpathCandidates.join(', ') }}
      </div>
    </div>
    <div class="manual">
      <select v-model="selectorType">
        <option value="css">CSS</option>
        <option value="xpath">XPath</option>
      </select>
      <input v-model="selectorText" placeholder="选择器，如 #submit-btn" class="sel-input" />
      <button @click="validateSelector">校验</button>
      <span v-if="validation">
        <span v-if="validation.valid" class="ok">匹配 {{ validation.count }} 个</span>
        <span v-else class="error">语法错误：{{ validation.error }}</span>
      </span>
    </div>
  </div>
</template>

<style>
body { margin: 0; font-family: sans-serif; }
.bar, .manual { display: flex; gap: 8px; padding: 8px; align-items: center; flex-wrap: wrap; }
.bar input { flex: 1; min-width: 200px; }
.sel-input { flex: 1; min-width: 200px; padding: 4px; }
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
.selection-info { padding: 8px; font-size: 0.85em; background: #f5f5f5; border-top: 1px solid #ddd; }
.selection-info .label { color: #888; margin-right: 4px; }
.manual { border-top: 1px solid #ddd; background: #fafafa; }
.ok { color: #080; font-weight: bold; }
.error { color: #c00; font-weight: bold; }
</style>
