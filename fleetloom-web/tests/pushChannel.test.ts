import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MAX_RETRY_DELAY_MS, PushChannel } from '@/push/channel'

class FakeSocket {
  onopen: (() => void) | null = null
  onmessage: ((e: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  closed = false
  constructor(readonly url: string) {}
  close() {
    this.closed = true
  }
  // test helpers
  open() {
    this.onopen?.()
  }
  receive(data: unknown) {
    this.onmessage?.({ data: typeof data === 'string' ? data : JSON.stringify(data) })
  }
  drop() {
    this.onclose?.()
  }
}

describe('PushChannel', () => {
  let sockets: FakeSocket[]
  let channel: PushChannel

  beforeEach(() => {
    vi.useFakeTimers()
    sockets = []
    channel = new PushChannel((t) => `ws://gw/ws?access_token=${t}`, (url) => {
      const s = new FakeSocket(url)
      sockets.push(s)
      return s as unknown as WebSocket
    })
  })

  afterEach(() => vi.useRealTimers())

  it('connects with the token and routes messages by type', () => {
    const seen: unknown[] = []
    channel.on('VehicleStateUpdated', (m) => seen.push(m.payload))
    channel.connect('abc')
    expect(sockets[0].url).toBe('ws://gw/ws?access_token=abc')
    expect(channel.status.value).toBe('connecting')

    sockets[0].open()
    expect(channel.status.value).toBe('online')
    sockets[0].receive({ type: 'VehicleStateUpdated', occurredAt: 'now', payload: { code: 'AGV-1' } })
    sockets[0].receive({ type: 'Other', occurredAt: 'now', payload: 1 })
    sockets[0].receive('not json')
    expect(seen).toEqual([{ code: 'AGV-1' }])
  })

  it('stops delivering after unsubscribe', () => {
    const handler = vi.fn()
    const off = channel.on('X', handler)
    channel.connect('t')
    sockets[0].open()
    off()
    sockets[0].receive({ type: 'X', occurredAt: '', payload: null })
    expect(handler).not.toHaveBeenCalled()
  })

  it('reconnects with growing delays and tells listeners to catch up', () => {
    const reopened = vi.fn()
    channel.onOpen(reopened)
    channel.connect('t')
    sockets[0].open()
    expect(reopened).toHaveBeenCalledTimes(1)

    sockets[0].drop()
    expect(channel.status.value).toBe('connecting')
    vi.advanceTimersByTime(999)
    expect(sockets).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(sockets).toHaveLength(2)

    sockets[1].drop()
    vi.advanceTimersByTime(1999)
    expect(sockets).toHaveLength(2)
    vi.advanceTimersByTime(1)
    expect(sockets).toHaveLength(3)

    sockets[2].open()
    expect(reopened).toHaveBeenCalledTimes(2)
  })

  it('caps the reconnect delay', () => {
    channel.connect('t')
    for (let i = 0; i < 10; i++) {
      sockets.at(-1)!.drop()
      vi.advanceTimersByTime(MAX_RETRY_DELAY_MS)
    }
    expect(sockets).toHaveLength(11)
  })

  it('stays down after disconnect', () => {
    channel.connect('t')
    sockets[0].open()
    channel.disconnect()
    expect(sockets[0].closed).toBe(true)
    expect(channel.status.value).toBe('offline')
    sockets[0].drop()
    vi.advanceTimersByTime(MAX_RETRY_DELAY_MS * 2)
    expect(sockets).toHaveLength(1)
  })

  it('replaces the connection when the token changes', () => {
    channel.connect('a')
    channel.connect('a')
    expect(sockets).toHaveLength(1)
    channel.connect('b')
    expect(sockets[0].closed).toBe(true)
    expect(sockets[1].url).toContain('access_token=b')
  })
})
