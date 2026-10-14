import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { RATE_LIMIT_PAUSE_MS, usePerchStatus } from '../hooks/usePerchStatus'
import type { PerchConfig } from '../lib/config'
import { jsonResponse, makeEvent, makeStatus } from '../test-utils/fixtures'

const config: PerchConfig = { controlUrl: 'http://phone:8080', token: 'tok', whepUrl: '' }

function urlsCalled(fetchMock: ReturnType<typeof vi.fn>): string[] {
  return (fetchMock.mock.calls as unknown as [string][]).map(([url]) => url.replace('http://phone:8080', ''))
}

async function tick(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

beforeEach(() => {
  vi.useFakeTimers()
})

describe('usePerchStatus', () => {
  it('does nothing without a configuration', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => usePerchStatus({ controlUrl: '', token: '', whepUrl: '' }))

    expect(result.current.connection).toBe('unconfigured')
    expect(result.current.client).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('goes from connecting to online and exposes status and events', async () => {
    const status = makeStatus()
    const events = [makeEvent({ id: 9 })]
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => (url.includes('/api/events') ? jsonResponse(events) : jsonResponse(status)))
    )

    const { result } = renderHook(() => usePerchStatus(config))
    expect(result.current.connection).toBe('connecting')
    await tick(0)

    expect(result.current.connection).toBe('online')
    expect(result.current.status).toEqual(status)
    expect(result.current.events).toEqual(events)
    expect(result.current.error).toBeNull()
  })

  it('polls status every 2s and events every 5s, and stops after unmount', async () => {
    const fetchMock = vi.fn(async (url: string) =>
      url.includes('/api/events') ? jsonResponse([]) : jsonResponse(makeStatus())
    )
    vi.stubGlobal('fetch', fetchMock)

    const { unmount } = renderHook(() => usePerchStatus(config))
    await tick(0)
    await tick(10_000)

    const urls = urlsCalled(fetchMock)
    expect(urls.filter(u => u === '/api/status')).toHaveLength(6)
    expect(urls.filter(u => u.startsWith('/api/events'))).toHaveLength(3)

    unmount()
    const before = fetchMock.mock.calls.length
    await tick(20_000)
    expect(fetchMock.mock.calls.length).toBe(before)
  })

  it('keeps the last good data but reports offline when the phone stops answering', async () => {
    const status = makeStatus()
    let failing = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (failing) throw new TypeError('Failed to fetch')
        return url.includes('/api/events') ? jsonResponse([makeEvent()]) : jsonResponse(status)
      })
    )
    const { result } = renderHook(() => usePerchStatus(config))
    await tick(0)
    expect(result.current.connection).toBe('online')

    failing = true
    await tick(2000)

    expect(result.current.connection).toBe('offline')
    expect(result.current.status).toEqual(status)
    expect(result.current.events).toHaveLength(1)
    expect(result.current.error).toMatch(/reach the phone/i)

    failing = false
    await tick(2000)
    expect(result.current.connection).toBe('online')
  })

  it('reports unauthorized and stops hammering the phone until refreshed', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ error: 'unauthorized' }, 401))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => usePerchStatus(config))
    await tick(0)
    expect(result.current.connection).toBe('unauthorized')

    const after401 = fetchMock.mock.calls.length
    await tick(10_000)
    expect(fetchMock.mock.calls.length).toBe(after401)

    act(() => result.current.refresh())
    await tick(0)
    expect(fetchMock.mock.calls.length).toBeGreaterThan(after401)
  })

  it('adopts a status returned by a control call', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => (url.includes('/api/events') ? jsonResponse([]) : jsonResponse(makeStatus())))
    )
    const { result } = renderHook(() => usePerchStatus(config))
    await tick(0)

    act(() => result.current.applyStatus(makeStatus({ flashlight: true })))

    expect(result.current.status?.flashlight).toBe(true)
  })

  it('starts polling events only after the first successful status', async () => {
    const fetchMock = vi.fn(async (url: string) =>
      url.includes('/api/events') ? jsonResponse([]) : jsonResponse({ error: 'unauthorized' }, 401)
    )
    vi.stubGlobal('fetch', fetchMock)

    renderHook(() => usePerchStatus(config))
    await tick(0)
    await tick(10_000)

    // One bad token costs exactly one authentication attempt.
    expect(urlsCalled(fetchMock)).toEqual(['/api/status'])
  })

  it('treats a 401 on the events poll like one on status', async () => {
    const fetchMock = vi.fn(async (url: string) =>
      url.includes('/api/events') ? jsonResponse({ error: 'unauthorized' }, 401) : jsonResponse(makeStatus())
    )
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => usePerchStatus(config))
    await tick(0)

    expect(result.current.connection).toBe('unauthorized')
    const before = fetchMock.mock.calls.length
    await tick(20_000)
    expect(fetchMock.mock.calls.length).toBe(before)
  })

  it('reports a 429 lockout, leaves the phone alone for a minute, then recovers', async () => {
    let locked = true
    const fetchMock = vi.fn(async (url: string) => {
      if (locked) return jsonResponse({ error: 'locked' }, 429)
      return url.includes('/api/events') ? jsonResponse([]) : jsonResponse(makeStatus())
    })
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => usePerchStatus(config))
    await tick(0)

    expect(result.current.connection).toBe('rate_limited')
    expect(result.current.error).toMatch(/too many attempts.*wait a minute/i)

    const during = fetchMock.mock.calls.length
    await tick(RATE_LIMIT_PAUSE_MS - 3000)
    expect(fetchMock.mock.calls.length).toBe(during)

    locked = false
    await tick(6000)
    expect(result.current.connection).toBe('online')
  })

  it('does not let a poll that started before a command overwrite the command result', async () => {
    let release: (response: Response) => void = () => undefined
    let slow = true
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.includes('/api/events')) return Promise.resolve(jsonResponse([]))
        if (slow) {
          slow = false
          return new Promise<Response>(resolve => (release = resolve))
        }
        return Promise.resolve(jsonResponse(makeStatus()))
      })
    )
    const { result } = renderHook(() => usePerchStatus(config))
    await tick(0)

    // The first poll is still in flight when a command result arrives.
    act(() => result.current.applyStatus(makeStatus({ flashlight: true })))
    await act(async () => {
      release(jsonResponse(makeStatus({ flashlight: false })))
      await vi.advanceTimersByTimeAsync(0)
    })

    expect(result.current.status?.flashlight).toBe(true)

    // The next poll is newer than the command and is applied normally.
    await tick(2000)
    expect(result.current.status?.flashlight).toBe(false)
  })

  it('refresh does nothing once the configuration is cleared', async () => {
    const fetchMock = vi.fn(async (url: string) =>
      url.includes('/api/events') ? jsonResponse([]) : jsonResponse(makeStatus())
    )
    vi.stubGlobal('fetch', fetchMock)
    const { result, rerender } = renderHook(({ cfg }) => usePerchStatus(cfg), { initialProps: { cfg: config } })
    await tick(0)

    rerender({ cfg: { controlUrl: '', token: '', whepUrl: '' } })
    const before = fetchMock.mock.calls.length
    act(() => result.current.refresh())
    await tick(10_000)

    expect(fetchMock.mock.calls.length).toBe(before)
    expect(result.current.client).toBeNull()
    expect(result.current.connection).toBe('unconfigured')
  })
})
