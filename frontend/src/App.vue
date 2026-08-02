<script setup lang="ts">
/**
 * App.vue：M3-6 #28 加入 Vue Router 后的全局壳。
 *
 * - 顶部 banner（HTTP 明文警告，复用 M1-4 既有 copy）；
 * - 顶头：版本 + 当前用户 + 登出（M1-4 沿用）+ 顶部导航（任务 / 运行）；
 * - <router-view> 渲染当前路由视图。
 *
 * 保持原有 fetchMe/handleLogout、原有 banner 与全局 CSS，仅追加 router-view 和导航栏。
 */
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
      <span class="version">v{{ APP_VERSION }} · M3 ready</span>
      <nav class="nav">
        <router-link to="/runs">运行</router-link>
        <router-link to="/tasks">任务</router-link>
      </nav>
      <div class="me">
        <span v-if="meStatus === 'authenticated'">已登录：<strong>{{ meUsername }}</strong></span>
        <span v-else-if="meStatus === 'anonymous'">未登录</span>
        <span v-else>查询中…</span>
        <button v-if="meStatus === 'authenticated'" @click="handleLogout">登出</button>
      </div>
    </header>
    <div v-if="meError" class="error">错误：{{ meError }}</div>
    <router-view />
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
  --color-border: #e5e7eb;
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
.app { max-width: 1280px; margin: 0 auto; padding: 1.5rem 1.5rem 2rem; }
.banner {
  background: var(--color-warning-bg);
  border-left: 4px solid var(--color-warning-border);
  padding: 0.75rem 1rem;
  margin-bottom: var(--space-section);
  border-radius: 4px;
  font-size: 0.9rem;
}
.header {
  display: flex; align-items: center; gap: 0.75rem;
  margin-bottom: 1.5rem;
  flex-wrap: wrap;
}
.header h1 { margin: 0; font-size: 1.5rem; }
.version { color: var(--color-muted); font-size: 0.9rem; margin-right: auto; }
.nav { display: flex; gap: 0.75rem; }
.nav a {
  text-decoration: none;
  color: var(--color-text);
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  font-size: 0.95rem;
}
.nav a.router-link-active {
  background: var(--color-accent);
  color: #fff;
}
.me { display: flex; gap: 0.5rem; align-items: center; font-size: 0.9rem; }
.me button {
  background: var(--color-accent);
  color: white;
  border: none;
  border-radius: 4px;
  padding: 0.35rem 0.8rem;
  cursor: pointer;
  font-size: 0.85rem;
}
.me button:hover { filter: brightness(0.95); }
.error { color: var(--color-error); margin-top: 0.5rem; font-size: 0.85rem; }
code { background: #f1f3f5; padding: 0.1em 0.4em; border-radius: 3px; font-size: 0.85em; }
</style>
