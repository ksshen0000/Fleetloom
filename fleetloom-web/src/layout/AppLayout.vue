<script setup lang="ts">
import { computed, onMounted, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElNotification } from 'element-plus'
import { ArrowDown, Bell, Box, Cpu, DataAnalysis, Document, Grid, List, Location, Monitor, Setting, User, Van } from '@element-plus/icons-vue'
import { authApi } from '@/api'
import type { Alarm } from '@/api/types'
import { act } from '@/composables/notify'
import { coalesce, usePush, usePushReconnect } from '@/composables/usePush'
import { ALARM_CATEGORY_LABELS } from '@/map/colors'
import { push } from '@/push/channel'
import { useAlarms } from '@/stores/alarms'
import { useSession } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const session = useSession()
const alarmStore = useAlarms()
const active = computed(() => route.path)

const ROLE_LABELS = { ADMIN: '管理员', OPERATOR: '操作员', VIEWER: '只读' }
const PUSH = {
  online: { type: 'success', text: '实时推送已连接' },
  connecting: { type: 'warning', text: '实时推送连接中' },
  offline: { type: 'info', text: '实时推送未连接' },
} as const

const items = computed(() =>
  [
    { path: '/monitor', title: '监控', icon: Monitor },
    { path: '/dashboard', title: '看板', icon: DataAnalysis },
    { path: '/orders', title: '订单', icon: List },
    { path: '/vehicles', title: '车辆', icon: Van },
    { path: '/maps', title: '地图', icon: Location },
    { path: '/storage', title: '库区库位', icon: Grid },
    { path: '/carriers', title: '载具', icon: Box },
    { path: '/alarms', title: '告警', icon: Bell },
    { path: '/simulator', title: '模拟器', icon: Cpu, write: true },
    { path: '/users', title: '用户', icon: User, admin: true },
    { path: '/audit', title: '审计日志', icon: Document, admin: true },
    { path: '/settings', title: '系统设置', icon: Setting, admin: true },
  ].filter((i) => (!i.admin || session.isAdmin) && (!i.write || session.canWrite)),
)

// ------------------------------------------------------------------ alarms: badge and pop-ups for serious new ones

const refreshAlarms = coalesce(() => alarmStore.refresh(), 300)
onMounted(refreshAlarms)
usePushReconnect(refreshAlarms)
usePush<{ alarm: Alarm }>('AlarmChanged', ({ payload: { alarm } }) => {
  refreshAlarms()
  const isNew = alarm.status === 'ACTIVE' && alarm.occurrences === 1
  if (isNew && alarm.level !== 'WARNING') {
    ElNotification({
      type: 'error',
      title: `${ALARM_CATEGORY_LABELS[alarm.category]}${alarm.vehicleCode ? ' · ' + alarm.vehicleCode : ''}`,
      message: alarm.message,
      duration: 8000,
      onClick: () => router.push('/alarms'),
    })
  }
})

const pwd = reactive({ open: false, current: '', next: '', again: '' })

async function changePassword() {
  if (pwd.next !== pwd.again) {
    ElMessage.error('两次输入的新密码不一致')
    return
  }
  if ((await act(() => authApi.changePassword(pwd.current, pwd.next), '密码已修改')) !== null) {
    Object.assign(pwd, { open: false, current: '', next: '', again: '' })
  }
}

function onCommand(command: string) {
  if (command === 'password') {
    pwd.open = true
  } else if (command === 'logout') {
    session.logout()
    router.replace({ name: 'login' })
  }
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="168px" class="aside">
      <div class="brand">Fleetloom</div>
      <el-menu :default-active="active" router>
        <el-menu-item v-for="item in items" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
          <el-badge
            v-if="item.path === '/alarms' && alarmStore.summary.unacknowledged > 0"
            :value="alarmStore.summary.unacknowledged"
            :max="99"
            class="badge"
            data-testid="alarm-badge"
          />
        </el-menu-item>
      </el-menu>
      <div class="aside-footer">
        <el-tooltip :content="PUSH[push.status.value].text" placement="right">
          <span class="push" :class="push.status.value" data-testid="push-status">
            <i />{{ push.status.value === 'online' ? '实时' : push.status.value === 'connecting' ? '连接中' : '离线' }}
          </span>
        </el-tooltip>
        <el-dropdown trigger="click" @command="onCommand">
          <span class="user">
            {{ session.user?.displayName }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ session.user?.username }} · {{ ROLE_LABELS[session.role ?? 'VIEWER'] }}</el-dropdown-item>
              <el-dropdown-item command="password" divided>修改密码</el-dropdown-item>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-aside>
    <el-main class="main">
      <router-view />
    </el-main>

    <el-dialog v-model="pwd.open" title="修改密码" width="380px">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="当前密码"><el-input v-model="pwd.current" type="password" show-password /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="pwd.next" type="password" show-password placeholder="至少 8 位" /></el-form-item>
        <el-form-item label="再次输入"><el-input v-model="pwd.again" type="password" show-password /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwd.open = false">取消</el-button>
        <el-button type="primary" :disabled="!pwd.current || !pwd.next" @click="changePassword">确定</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}
.aside {
  border-right: 1px solid var(--el-border-color-light);
  display: flex;
  flex-direction: column;
}
.aside .el-menu {
  border-right: none;
  flex: 1;
}
.brand {
  font-weight: 700;
  font-size: 18px;
  padding: 16px 20px;
  letter-spacing: 1px;
}
.aside-footer {
  padding: 12px 20px;
  border-top: 1px solid var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
}
.push {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.push i {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  background: var(--el-color-info);
}
.push.online i {
  background: var(--el-color-success);
}
.push.connecting i {
  background: var(--el-color-warning);
}
.user {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.badge {
  margin-left: 8px;
  line-height: 1;
}
.main {
  padding: 0;
  height: 100%;
  overflow: hidden;
}
</style>
