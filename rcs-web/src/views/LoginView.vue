<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { useSession } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const session = useSession()

const form = reactive({ username: '', password: '' })
const error = ref<string | null>(route.query.expired ? '登录已过期，请重新登录' : null)
const busy = ref(false)

async function submit() {
  busy.value = true
  error.value = null
  try {
    await session.login(form.username, form.password)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/monitor'
    await router.replace(redirect)
  } catch (e) {
    if (e instanceof ApiError && e.code === 'LOGIN_FAILED') error.value = '用户名或密码错误'
    else if (e instanceof ApiError && e.code === 'TOO_MANY_LOGIN_ATTEMPTS') error.value = '登录失败次数过多，请稍后再试'
    else error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login">
    <el-card class="card" shadow="never">
      <h2>RCS3000</h2>
      <p class="sub">AGV 调度管理平台</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" autofocus />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="error" />
        <el-button type="primary" native-type="submit" :loading="busy" :disabled="!form.username || !form.password" class="submit">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light);
}
.card {
  width: 340px;
}
h2 {
  margin: 0;
  letter-spacing: 1px;
}
.sub {
  margin: 4px 0 20px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.error {
  margin-bottom: 12px;
}
.submit {
  width: 100%;
}
</style>
