import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * Runs {@code task} now and then every {@code intervalMs} while the component is mounted. A failure
 * is reported once through {@code error}; it clears when a later run succeeds. The interval may be a
 * function, read before each wait, e.g. to poll less while push is connected.
 */
export function usePolling(task: () => Promise<void>, intervalMs: number | (() => number)) {
  const error = ref<string | null>(null)
  let timer: ReturnType<typeof setTimeout> | null = null
  let stopped = false

  async function run() {
    try {
      await task()
      error.value = null
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
    }
    if (!stopped) {
      timer = setTimeout(run, typeof intervalMs === 'number' ? intervalMs : intervalMs())
    }
  }

  onMounted(run)
  onBeforeUnmount(() => {
    stopped = true
    if (timer) {
      clearTimeout(timer)
    }
  })

  return { error, refresh: task }
}

/** A clock ticking on every animation frame, for smooth drawing. */
export function useAnimationClock() {
  const now = ref(performance.now())
  let handle = 0
  const tick = () => {
    now.value = performance.now()
    handle = requestAnimationFrame(tick)
  }
  onMounted(() => (handle = requestAnimationFrame(tick)))
  onBeforeUnmount(() => cancelAnimationFrame(handle))
  return now
}
