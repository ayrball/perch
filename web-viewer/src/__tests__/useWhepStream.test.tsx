import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { StrictMode } from 'react'
import { DISCONNECT_GRACE_MS, useWhepStream } from '../hooks/useWhepStream'
import { FakePeerConnection, installFakeRtc, whepFetchMock } from '../test-utils/fakeRtc'

const WHEP_URL = 'http://192.168.1.100:8889/perch/whep'

async function flush() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
}

beforeEach(() => {
  vi.useFakeTimers()
  installFakeRtc()
})

describe('useWhepStream', () => {
  it('stays unconfigured without a URL', () => {
    const { result } = renderHook(() => useWhepStream(null))

    expect(result.current.state).toBe('unconfigured')
    expect(FakePeerConnection.instances).toHaveLength(0)
  })

  it('connects, reports live, and publishes real stats', async () => {
    vi.stubGlobal('fetch', whepFetchMock())
    const { result } = renderHook(() => useWhepStream(WHEP_URL))
    expect(result.current.state).toBe('connecting')
    await flush()
    const pc = FakePeerConnection.instances[0]

    pc.stats.set('v', {
      type: 'inbound-rtp',
      kind: 'video',
      bytesReceived: 0,
      timestamp: 1000,
      frameWidth: 1280,
      frameHeight: 720,
      framesPerSecond: 30
    })
    act(() => pc.simulateState('connected'))
    expect(result.current.state).toBe('live')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000)
    })
    pc.stats.set('v', { ...pc.stats.get('v'), bytesReceived: 250_000, timestamp: 3000 })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000)
    })

    expect(result.current.stats).toEqual({ bitrateBps: 1_000_000, width: 1280, height: 720, fps: 30 })
  })

  it('reconnects with exponential backoff after a failure', async () => {
    const fetchMock = whepFetchMock()
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useWhepStream(WHEP_URL))
    await flush()

    act(() => FakePeerConnection.instances[0].simulateState('failed'))
    expect(result.current.state).toBe('reconnecting')
    expect(FakePeerConnection.instances[0].closed).toBe(true)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(999)
    })
    expect(FakePeerConnection.instances).toHaveLength(1)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2)
    })
    expect(FakePeerConnection.instances).toHaveLength(2)

    act(() => FakePeerConnection.instances[1].simulateState('failed'))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1999)
    })
    expect(FakePeerConnection.instances).toHaveLength(2)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2)
    })
    expect(FakePeerConnection.instances).toHaveLength(3)

    act(() => FakePeerConnection.instances[2].simulateState('connected'))
    expect(result.current.state).toBe('live')
  })

  it('retries when the WHEP POST fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 503 })))
    const { result } = renderHook(() => useWhepStream(WHEP_URL))
    await flush()

    expect(result.current.state).toBe('reconnecting')
    expect(result.current.error).toBe('Stream server returned HTTP 503.')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1001)
    })
    expect(FakePeerConnection.instances).toHaveLength(2)
  })

  it('sends DELETE to the session resource and closes the connection on unmount', async () => {
    const fetchMock = whepFetchMock({ location: '/perch/whep/session-7' })
    vi.stubGlobal('fetch', fetchMock)
    const { unmount } = renderHook(() => useWhepStream(WHEP_URL))
    await flush()
    const pc = FakePeerConnection.instances[0]

    unmount()

    expect(pc.closed).toBe(true)
    expect(fetchMock).toHaveBeenCalledWith(
      'http://192.168.1.100:8889/perch/whep/session-7',
      expect.objectContaining({ method: 'DELETE' })
    )
    await vi.advanceTimersByTimeAsync(20_000)
    expect(FakePeerConnection.instances).toHaveLength(1)
  })

  describe('transient disconnects', () => {
    it('waits out a brief "disconnected" and keeps the stream when it recovers', async () => {
      vi.stubGlobal('fetch', whepFetchMock())
      const { result } = renderHook(() => useWhepStream(WHEP_URL))
      await flush()
      const pc = FakePeerConnection.instances[0]
      act(() => pc.simulateState('connected'))

      act(() => pc.simulateState('disconnected'))
      await act(async () => {
        await vi.advanceTimersByTimeAsync(DISCONNECT_GRACE_MS - 1)
      })
      act(() => pc.simulateState('connected'))
      await act(async () => {
        await vi.advanceTimersByTimeAsync(30_000)
      })

      expect(pc.closed).toBe(false)
      expect(FakePeerConnection.instances).toHaveLength(1)
      expect(result.current.state).toBe('live')
    })

    it('reconnects when "disconnected" lasts longer than the grace period', async () => {
      vi.stubGlobal('fetch', whepFetchMock())
      const { result } = renderHook(() => useWhepStream(WHEP_URL))
      await flush()
      const pc = FakePeerConnection.instances[0]
      act(() => pc.simulateState('connected'))

      act(() => pc.simulateState('disconnected'))
      await act(async () => {
        await vi.advanceTimersByTimeAsync(DISCONNECT_GRACE_MS - 1)
      })
      expect(pc.closed).toBe(false)
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2)
      })

      expect(pc.closed).toBe(true)
      expect(result.current.state).toBe('reconnecting')
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1001)
      })
      expect(FakePeerConnection.instances).toHaveLength(2)
    })

    it('treats "failed" and "closed" as immediate failures', async () => {
      vi.stubGlobal('fetch', whepFetchMock())
      const { result } = renderHook(() => useWhepStream(WHEP_URL))
      await flush()

      act(() => FakePeerConnection.instances[0].simulateState('closed'))

      expect(result.current.state).toBe('reconnecting')
    })
  })

  describe('teardown races', () => {
    it('aborts a pending WHEP POST on unmount without leaking a connection or retrying', async () => {
      const fetchMock = whepFetchMock({ hold: true })
      vi.stubGlobal('fetch', fetchMock)
      const { unmount } = renderHook(() => useWhepStream(WHEP_URL))
      await flush()
      const post = fetchMock.mock.calls[0][1] as RequestInit

      unmount()
      await flush()

      expect(post.signal?.aborted).toBe(true)
      expect(FakePeerConnection.instances[0].closed).toBe(true)
      expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(0)
      await vi.advanceTimersByTimeAsync(30_000)
      expect(FakePeerConnection.instances).toHaveLength(1)
    })

    it('stops cleanly when unmounted before the offer exists', async () => {
      FakePeerConnection.holdOffer = true
      const fetchMock = whepFetchMock()
      vi.stubGlobal('fetch', fetchMock)
      const { unmount } = renderHook(() => useWhepStream(WHEP_URL))
      await flush()

      unmount()
      await flush()

      expect(FakePeerConnection.instances[0].closed).toBe(true)
      expect(fetchMock).not.toHaveBeenCalled()
      await vi.advanceTimersByTimeAsync(30_000)
      expect(FakePeerConnection.instances).toHaveLength(1)
    })

    it('deletes the server session when unmounted while the answer is being applied', async () => {
      FakePeerConnection.holdAnswer = true
      const fetchMock = whepFetchMock({ location: '/perch/whep/session-3' })
      vi.stubGlobal('fetch', fetchMock)
      const { unmount } = renderHook(() => useWhepStream(WHEP_URL))
      await flush()

      unmount()
      await flush()

      expect(fetchMock).toHaveBeenCalledWith(
        'http://192.168.1.100:8889/perch/whep/session-3',
        expect.objectContaining({ method: 'DELETE' })
      )
      await vi.advanceTimersByTimeAsync(30_000)
      expect(FakePeerConnection.instances).toHaveLength(1)
    })

    it('cleans up a reconnect attempt that is still negotiating when the component unmounts', async () => {
      const fetchMock = whepFetchMock()
      vi.stubGlobal('fetch', fetchMock)
      const { unmount } = renderHook(() => useWhepStream(WHEP_URL))
      await flush()
      act(() => FakePeerConnection.instances[0].simulateState('failed'))

      FakePeerConnection.holdOffer = true
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1001)
      })
      expect(FakePeerConnection.instances).toHaveLength(2)

      unmount()
      await flush()

      expect(FakePeerConnection.instances[1].closed).toBe(true)
      await vi.advanceTimersByTimeAsync(30_000)
      expect(FakePeerConnection.instances).toHaveLength(2)
    })

    it('leaves exactly one live connection after a StrictMode double mount', async () => {
      vi.stubGlobal('fetch', whepFetchMock())
      const { result } = renderHook(() => useWhepStream(WHEP_URL), { wrapper: StrictMode })
      await flush()

      const open = FakePeerConnection.instances.filter(pc => !pc.closed)
      expect(open).toHaveLength(1)
      act(() => open[0].simulateState('connected'))
      expect(result.current.state).toBe('live')
      await vi.advanceTimersByTimeAsync(30_000)
      expect(FakePeerConnection.instances.filter(pc => !pc.closed)).toHaveLength(1)
    })

    it('ends the session when the page is hidden (tab closed)', async () => {
      const fetchMock = whepFetchMock({ location: '/perch/whep/session-5' })
      vi.stubGlobal('fetch', fetchMock)
      renderHook(() => useWhepStream(WHEP_URL))
      await flush()
      const pc = FakePeerConnection.instances[0]

      act(() => {
        window.dispatchEvent(new Event('pagehide'))
      })

      expect(pc.closed).toBe(true)
      const deleteCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'DELETE')
      expect(deleteCall?.[0]).toBe('http://192.168.1.100:8889/perch/whep/session-5')
      expect(deleteCall?.[1]).toEqual(expect.objectContaining({ keepalive: true }))
    })
  })
})
