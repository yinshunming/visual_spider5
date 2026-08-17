/**
 * Vue Router 路由表（M3-6 #28）。
 *
 * - {@code /} 重定向到 {@code /runs}；
 * - {@code /runs}：运行列表（轮询 + filter + 取消 + 导出）；
 * - {@code /runs/:id}：运行详情（WS 进度 + 快照 + 结果 + 事件 + 导出，无画面）；
 * - {@code /tasks}：任务列表（M3-6 启动入口，仅 {@code READY} 可点）；
 * - 其它路径兜底重定向。
 *
 * 使用 createWebHistory 让 URL 在浏览器地址栏可分享；后端 Spring Boot 在
 * {@code 参见 application.yml 的 spring.web.resources.static-locations} 提供静态资源，
 * Spring 会把未命中的 GET 落到 {@code index.html}，history 模式可工作。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import RunListView from '../views/RunListView.vue'
import RunDetailView from '../views/RunDetailView.vue'
import TaskListView from '../views/TaskListView.vue'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/runs' },
  { path: '/runs', name: 'runs', component: RunListView },
  { path: '/runs/:id(\\d+)', name: 'run-detail', component: RunDetailView, props: true },
  { path: '/tasks', name: 'tasks', component: TaskListView },
  { path: '/:catchAll(.*)', redirect: '/runs' },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})
