import { ref } from 'vue'
import type { PushMessage } from '@/api/types'

export type PushStatus = 'offline' | 'connecting' | 'online'
type Handler = (message: PushMessage) => void

/** Longest wait between reconnect attempts. */
export const MAX_RETRY_DELAY_MS = 30_000

/**
 * The gateway's /ws push channel. Reconnects with exponential backoff while a token is set. Messages
 * sent while disconnected are lost, so {@link onOpen} listeners reload what they show.
 */
export class PushChannel {
  readonly status = ref<PushStatus>('offline')
  private socket: WebSocket | null = null
  private token: string | null = null
  private attempts = 0
  private timer: ReturnType<typeof setTimeout> | null = null
  private readonly handlers = new Map<string, Set<Handler>>()
  private readonly openListeners = new Set<() => void>()

  constructor(
    private readonly url: (token: string) => string,
    private readonly createSocket: (url: string) => WebSocket = (u) => new WebSocket(u),
  ) {}

  connect(token: string) {
    if (token === this.token && this.socket) {
      return
    }
    this.disconnect()
    this.token = token
    this.open()
  }

  disconnect() {
    this.token = null
    this.attempts = 0
    if (this.timer) {
      clearTimeout(this.timer)
      this.timer = null
    }
    const socket = this.socket
    this.socket = null
    socket?.close()
    this.status.value = 'offline'
  }

  /** Subscribes to one event type; returns the unsubscribe function. */
  on(type: string, handler: Handler): () => void {
    let set = this.handlers.get(type)
    if (!set) {
      set = new Set()
      this.handlers.set(type, set)
    }
    set.add(handler)
    return () => set.delete(handler)
  }

  /** Called on every successful (re)connect. */
  onOpen(listener: () => void): () => void {
    this.openListeners.add(listener)
    return () => this.openListeners.delete(listener)
  }

  private open() {
    if (!this.token) {
      return
    }
    this.status.value = 'connecting'
    const socket = this.createSocket(this.url(this.token))
    this.socket = socket
    socket.onopen = () => {
      if (socket !== this.socket) return
      this.attempts = 0
      this.status.value = 'online'
      this.openListeners.forEach((l) => l())
    }
    socket.onmessage = (event: MessageEvent) => {
      if (socket !== this.socket) return
      let message: PushMessage
      try {
        message = JSON.parse(String(event.data)) as PushMessage
      } catch {
        return
      }
      this.handlers.get(message.type)?.forEach((h) => h(message))
    }
    socket.onclose = () => {
      if (socket !== this.socket) return
      this.socket = null
      this.status.value = 'connecting'
      const delay = Math.min(MAX_RETRY_DELAY_MS, 1000 * 2 ** this.attempts++)
      this.timer = setTimeout(() => {
        this.timer = null
        this.open()
      }, delay)
    }
  }
}

export const push = new PushChannel(
  (token) => `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws?access_token=${encodeURIComponent(token)}`,
)
