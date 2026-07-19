<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { http, ApiError } from './http'

// 前端版本号由 Vite 在构建时注入；M1-4 spec §D11 接受运行时常量
const APP_VERSION = __APP_VERSION__

const meStatus = ref<'unknown' | 'authenticated' | 'anonymous'>('unknown')
const meUsername = ref<string>('')
const meError = ref<string>('')

interface MeResponse {
  actorId: number
  username: string
  role: string
}

async function fetchMe(): Promise<void> {
  try {
    const me = await http.get<MeResponse>('/api/identity/me')
    meStatus.value = 'authenticated'
    meUsername.value = me.username
    meError.value = ''
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      meStatus.value = 'anonymous'
      meError.value = ''
    } else {
      meStatus.value = 'unknown'
      meError.value = e instanceof Error ? e.message : String(e)
    }
  }
}

function handleLogout(): void {
  void http.post('/api/auth/logout').finally(fetchMe)
}

onMounted(fetchMe)
</script>

<template>
  <div class="app">
    <div class="banner" role="alert">
      ⚠️ HTTP 明文传输：本应用首版未启用 HTTPS，登录凭据与页面内容以明文传输。
      必须部署在可信 LAN / VPN 内。
    </div>
    <header class="header">
      <h1>Visual Spider 5</h1>
      <span class="version">v{{ APP_VERSION }} · M1 ready</span>
    </header>
    <main class="main">
      <section class="card">
        <h2>身份</h2>
        <div v-if="meStatus === 'authenticated'">
          已登录：<strong>{{ meUsername }}</strong>
          <button @click="handleLogout">登出</button>
        </div>
        <div v-else-if="meStatus === 'anonymous'">未登录</div>
        <div v-else>查询中…</div>
        <div v-if="meError" class="error">错误：{{ meError }}</div>
      </section>
      <section class="card">
        <h2>登录</h2>
        <p>M1-4 阶段不实现登录表单 UI；通过 <code>POST /api/auth/login</code> 调用后端登录端点。
          产品 UI 在后续 milestone 落地。</p>
      </section>
      <section class="card">
        <h2>任务（M1-4 暂未落地）</h2>
        <p>任务 CRUD 与预览功能在后续 milestone 启用。</p>
      </section>
    </main>
  </div>
</template>

<style>
:root {
  --color-bg: #f7f8fa;
  --color-surface: #ffffff;
  --color-text: #1f2328;
  --color-muted: #6b7280;
  --color-accent: #2563eb;
  --color-warning-bg: #fff8e1;
  --color-warning-border: #f59e0b;
  --color-error: #b91c1c;
  --space-section: 1.5rem;
  --radius-card: 8px;
  --shadow-card: 0 1px 3px rgba(0, 0, 0, 0.06), 0 1px 2px rgba(0, 0, 0, 0.04);
}
* { box-sizing: border-box; }
body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  background: var(--color-bg);
  color: var(--color-text);
}
.app { max-width: 720px; margin: 0 auto; padding: 2rem 1.5rem; }
.banner {
  background: var(--color-warning-bg);
  border-left: 4px solid var(--color-warning-border);
  padding: 0.75rem 1rem;
  margin-bottom: var(--space-section);
  border-radius: 4px;
  font-size: 0.9rem;
}
.header {
  display: flex; align-items: baseline; gap: 0.75rem;
  margin-bottom: 1.5rem;
}
.header h1 { margin: 0; font-size: 1.6rem; }
.version { color: var(--color-muted); font-size: 0.9rem; }
.main { display: flex; flex-direction: column; gap: var(--space-section); }
.card {
  background: var(--color-surface);
  border-radius: var(--radius-card);
  padding: 1.25rem;
  box-shadow: var(--shadow-card);
}
.card h2 { margin: 0 0 0.5rem 0; font-size: 1.1rem; }
.card p { margin: 0.25rem 0; color: var(--color-muted); font-size: 0.9rem; }
.card button {
  background: var(--color-accent);
  color: white;
  border: none;
  border-radius: 4px;
  padding: 0.45rem 1rem;
  cursor: pointer;
  font-size: 0.9rem;
}
.card button:hover { filter: brightness(0.95); }
.error { color: var(--color-error); margin-top: 0.5rem; font-size: 0.85rem; }
code { background: #f1f3f5; padding: 0.1em 0.4em; border-radius: 3px; font-size: 0.85em; }
</style>
