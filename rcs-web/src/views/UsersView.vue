<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { usersApi } from '@/api'
import type { Role, UserView } from '@/api/types'
import { act, confirm, formatTime } from '@/composables/notify'
import { useSession } from '@/stores/session'

const session = useSession()
const users = ref<UserView[]>([])
const ROLES: { value: Role; label: string; hint: string }[] = [
  { value: 'ADMIN', label: '管理员', hint: '全部操作，含用户管理' },
  { value: 'OPERATOR', label: '操作员', hint: '下单、控制车辆、维护地图和库位' },
  { value: 'VIEWER', label: '只读', hint: '只能查看' },
]
const roleLabel = (r: Role) => ROLES.find((x) => x.value === r)?.label ?? r

async function load() {
  users.value = await usersApi.list()
}

onMounted(load)

const dialog = reactive({ open: false, editing: false })
const form = reactive({ username: '', displayName: '', password: '', role: 'OPERATOR' as Role, enabled: true })

function open(u?: UserView) {
  Object.assign(dialog, { open: true, editing: !!u })
  Object.assign(form, u ? { ...u, password: '' } : { username: '', displayName: '', password: '', role: 'OPERATOR', enabled: true })
}

async function save() {
  const done = dialog.editing
    ? await act(() => usersApi.update(form.username, { displayName: form.displayName, role: form.role, enabled: form.enabled }), '已保存')
    : await act(() => usersApi.create({ username: form.username, displayName: form.displayName, password: form.password, role: form.role }), '已创建')
  if (done) {
    dialog.open = false
    await load()
  }
}

const reset = reactive({ open: false, username: '', password: '' })

function openReset(u: UserView) {
  Object.assign(reset, { open: true, username: u.username, password: '' })
}

async function resetPassword() {
  if ((await act(() => usersApi.resetPassword(reset.username, reset.password), '密码已重置')) !== null) {
    reset.open = false
  }
}

async function remove(u: UserView) {
  if (await confirm(`删除用户 ${u.username}？`)) {
    await act(() => usersApi.remove(u.username), '已删除')
    await load()
  }
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-button @click="load">刷新</el-button>
      <span class="spacer" />
      <el-button type="primary" @click="open()">新建用户</el-button>
    </div>
    <el-table :data="users" size="small" stripe>
      <el-table-column prop="username" label="用户名" width="160" />
      <el-table-column prop="displayName" label="姓名" width="160" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">{{ roleLabel(row.role) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="200">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">编辑</el-button>
          <el-button link @click="openReset(row)">重置密码</el-button>
          <el-button link type="danger" :disabled="row.username === session.user?.username" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog.open" :title="dialog.editing ? '编辑用户' : '新建用户'" width="420px">
      <el-form label-width="70px">
        <el-form-item label="用户名"><el-input v-model="form.username" :disabled="dialog.editing" placeholder="字母、数字、_ . -" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.displayName" /></el-form-item>
        <el-form-item v-if="!dialog.editing" label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="form.role">
            <el-radio v-for="r in ROLES" :key="r.value" :value="r.value">{{ r.label }}</el-radio>
          </el-radio-group>
          <div class="hint">{{ ROLES.find((r) => r.value === form.role)?.hint }}</div>
        </el-form-item>
        <el-form-item v-if="dialog.editing" label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reset.open" :title="`重置 ${reset.username} 的密码`" width="380px">
      <el-input v-model="reset.password" type="password" show-password placeholder="新密码，至少 8 位" />
      <template #footer>
        <el-button @click="reset.open = false">取消</el-button>
        <el-button type="primary" :disabled="!reset.password" @click="resetPassword">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint {
  width: 100%;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
