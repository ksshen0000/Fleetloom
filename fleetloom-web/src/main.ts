import { createApp, watch } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import VueKonva from 'vue-konva'
import App from './App.vue'
import { auth } from './api/http'
import { push } from './push/channel'
import { router } from './router'
import { useSession } from './stores/session'
import './styles.css'

const pinia = createPinia()
const session = useSession(pinia)

auth.token = () => session.token
auth.onUnauthorized = () => {
  if (!session.token) return
  session.logout()
  const here = router.currentRoute.value
  if (!here.meta.public) {
    router.replace({ name: 'login', query: { redirect: here.fullPath, expired: '1' } })
  }
}

// the push channel lives as long as the session
watch(
  () => session.token,
  (token) => (token ? push.connect(token) : push.disconnect()),
  { immediate: true },
)

createApp(App).use(pinia).use(router).use(ElementPlus, { locale: zhCn }).use(VueKonva).mount('#app')
