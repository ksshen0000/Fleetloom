import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api'
import type { LoginResult, Role } from '@/api/types'

const STORAGE_KEY = 'rcs.session'

function load(): LoginResult | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const session = raw ? (JSON.parse(raw) as LoginResult) : null
    return session && Date.parse(session.expiresAt) > Date.now() ? session : null
  } catch {
    return null
  }
}

function store(session: LoginResult | null) {
  try {
    if (session) localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // private mode or blocked storage: the session simply ends with the tab
  }
}

/** The logged-in user and their access token (issued by rcs-resource, checked by rcs-gateway). */
export const useSession = defineStore('session', () => {
  const current = ref<LoginResult | null>(load())

  const token = computed(() => current.value?.token ?? null)
  const user = computed(() => current.value?.user ?? null)
  const role = computed<Role | null>(() => user.value?.role ?? null)
  const isAdmin = computed(() => role.value === 'ADMIN')
  /** VIEWER may only look; the gateway rejects their changes anyway. */
  const canWrite = computed(() => role.value === 'ADMIN' || role.value === 'OPERATOR')

  async function login(username: string, password: string) {
    current.value = await authApi.login(username, password)
    store(current.value)
  }

  function logout() {
    current.value = null
    store(null)
  }

  return { token, user, role, isAdmin, canWrite, login, logout }
})
