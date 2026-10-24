import { onBeforeUnmount } from 'vue'
import type { PushMessage } from '@/api/types'
import { push } from '@/push/channel'

/** Handles pushed events of the given types while the component is mounted. */
export function usePush<T = unknown>(types: string | string[], handler: (message: PushMessage<T>) => void) {
  const off = (Array.isArray(types) ? types : [types]).map((t) => push.on(t, handler as (m: PushMessage) => void))
  onBeforeUnmount(() => off.forEach((f) => f()))
}

/** Runs {@code reload} whenever the push channel (re)connects, to catch up on what was missed. */
export function usePushReconnect(reload: () => void) {
  const off = push.onOpen(reload)
  onBeforeUnmount(off)
}

/** Coalesces bursts of calls into one, {@code waitMs} after the first. */
export function coalesce(task: () => Promise<unknown>, waitMs = 200): () => void {
  let pending = false
  return () => {
    if (pending) return
    pending = true
    setTimeout(() => {
      pending = false
      task().catch(() => {})
    }, waitMs)
  }
}
