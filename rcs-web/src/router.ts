import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import type { Role } from './api/types'
import { useSession } from './stores/session'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** Reachable without logging in. */
    public?: boolean
    /** Only these roles see the page. */
    roles?: Role[]
  }
}

export const pages: RouteRecordRaw[] = [
  { path: 'dashboard', name: 'dashboard', meta: { title: '看板' }, component: () => import('./views/DashboardView.vue') },
  { path: 'monitor', name: 'monitor', meta: { title: '监控' }, component: () => import('./views/MonitorView.vue') },
  { path: 'orders', name: 'orders', meta: { title: '订单' }, component: () => import('./views/OrdersView.vue') },
  { path: 'vehicles', name: 'vehicles', meta: { title: '车辆' }, component: () => import('./views/VehiclesView.vue') },
  { path: 'maps', name: 'maps', meta: { title: '地图' }, component: () => import('./views/MapEditorView.vue') },
  { path: 'storage', name: 'storage', meta: { title: '库区库位' }, component: () => import('./views/StorageView.vue') },
  { path: 'carriers', name: 'carriers', meta: { title: '载具' }, component: () => import('./views/CarriersView.vue') },
  { path: 'alarms', name: 'alarms', meta: { title: '告警' }, component: () => import('./views/AlarmsView.vue') },
  { path: 'simulator', name: 'simulator', meta: { title: '模拟器', roles: ['ADMIN', 'OPERATOR'] }, component: () => import('./views/SimulatorView.vue') },
  { path: 'audit', name: 'audit', meta: { title: '审计日志', roles: ['ADMIN'] }, component: () => import('./views/AuditView.vue') },
  { path: 'settings', name: 'settings', meta: { title: '系统设置', roles: ['ADMIN'] }, component: () => import('./views/SettingsView.vue') },
  { path: 'users', name: 'users', meta: { title: '用户', roles: ['ADMIN'] }, component: () => import('./views/UsersView.vue') },
]

export const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', meta: { title: '登录', public: true }, component: () => import('./views/LoginView.vue') },
  { path: '/', component: () => import('./layout/AppLayout.vue'), redirect: '/monitor', children: pages },
  { path: '/:rest(.*)*', redirect: '/monitor' },
]

export const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const session = useSession()
  if (to.meta.public) {
    return true
  }
  if (!session.token) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  if (to.meta.roles && !to.meta.roles.includes(session.role!)) {
    return { name: 'monitor' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - RCS3000` : 'RCS3000'
})
