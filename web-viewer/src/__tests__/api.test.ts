import { describe, it, expect, vi } from 'vitest'
import {
  HttpError,
  NetworkError,
  PerchClient,
  REQUEST_TIMEOUT_MS,
  RateLimitedError,
  UnauthorizedError,
  normalizeEvents,
  normalizeStatus
} from '../lib/api'
import { jsonResponse, makeEvent, makeStatus } from '../test-utils/fixtures'

function client() {
  return new PerchClient({ controlUrl: 'http://phone.local:8080/', token: 'secret-token' })
}

type FetchCall = [string, RequestInit]

function callsOf(fetchMock: ReturnType<typeof vi.fn>): FetchCall[] {
  return fetchMock.mock.calls as unknown as FetchCall[]
}

function headersOf(call: FetchCall): Record<string, string> {
  return call[1].headers as Record<string, string>
}

describe('PerchClient', () => {
  it('sends the bearer token and parses the status', async () => {
    const status = makeStatus()
    const fetchMock = vi.fn(async () => jsonResponse(status))
    vi.stubGlobal('fetch', fetchMock)

    await expect(client().getStatus()).resolves.toEqual(status)

    const [call] = callsOf(fetchMock)
    expect(call[0]).toBe('http://phone.local:8080/api/status')
    expect(headersOf(call).Authorization).toBe('Bearer secret-token')
    expect(call[1].method).toBe('GET')
  })

  it('requests events with a limit', async () => {
    const fetchMock = vi.fn(async () => jsonResponse([makeEvent()]))
    vi.stubGlobal('fetch', fetchMock)

    const events = await client().getEvents(10)

    expect(events).toHaveLength(1)
    expect(callsOf(fetchMock)[0][0]).toBe('http://phone.local:8080/api/events?limit=10')
  })

  it('posts JSON bodies for control commands, always with the token', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(makeStatus()))
    vi.stubGlobal('fetch', fetchMock)
    const api = client()

    await api.setFlashlight(true)
    await api.startSiren(10)
    await api.stopSiren()
    await api.flipCamera()
    await api.setQuality('720p')

    const calls = callsOf(fetchMock)
    expect(calls.map(([url]) => url.replace('http://phone.local:8080', ''))).toEqual([
      '/api/flashlight',
      '/api/siren',
      '/api/siren/stop',
      '/api/camera/flip',
      '/api/quality'
    ])
    expect(calls.map(([, init]) => init.method)).toEqual(['POST', 'POST', 'POST', 'POST', 'POST'])
    expect(calls[0][1].body).toBe('{"on":true}')
    expect(calls[1][1].body).toBe('{"seconds":10}')
    expect(calls[2][1].body).toBeUndefined()
    expect(calls[4][1].body).toBe('{"quality":"720p"}')
    expect(headersOf(calls[0])['Content-Type']).toBe('application/json')
    expect(headersOf(calls[2])['Content-Type']).toBeUndefined()
    for (const call of calls) {
      expect(headersOf(call).Authorization).toBe('Bearer secret-token')
    }
  })

  it('omits the body to toggle the flashlight', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(makeStatus()))
    vi.stubGlobal('fetch', fetchMock)

    await client().setFlashlight()

    expect(callsOf(fetchMock)[0][1].body).toBeUndefined()
  })

  it('maps 401 to UnauthorizedError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ error: 'bad token' }, 401)))

    await expect(client().getStatus()).rejects.toBeInstanceOf(UnauthorizedError)
  })

  it('maps other failures to HttpError using the error message from the phone', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ error: 'Camera busy' }, 409)))

    const error = await client().flipCamera().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(HttpError)
    expect((error as HttpError).status).toBe(409)
    expect((error as HttpError).message).toBe('Camera busy')
  })

  it('falls back to a generic message when the error body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('oops', { status: 500 })))

    await expect(client().getStatus()).rejects.toThrow('Phone returned HTTP 500.')
  })

  it('maps fetch failures to NetworkError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))

    await expect(client().getStatus()).rejects.toBeInstanceOf(NetworkError)
  })

  it('aborts and reports a NetworkError after the timeout', async () => {
    vi.useFakeTimers()
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url: string, init: RequestInit) =>
          new Promise((_resolve, reject) => {
            init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
          })
      )
    )

    const outcome = client().getStatus().catch((e: unknown) => e)
    await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS + 1)

    const error = await outcome
    expect(error).toBeInstanceOf(NetworkError)
    expect((error as NetworkError).message).toMatch(/did not respond/)
  })

  it('maps 429 to RateLimitedError with a clear message', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ error: 'locked' }, 429)))

    const error = await client().getStatus().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(RateLimitedError)
    expect(error).toBeInstanceOf(HttpError)
    expect((error as RateLimitedError).status).toBe(429)
    expect((error as RateLimitedError).message).toMatch(/wait a minute/i)
  })

  it('keeps the timeout armed while the response body is being read', async () => {
    vi.useFakeTimers()
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init: RequestInit) => {
        // Headers arrive immediately, but the body never finishes until the request is aborted.
        const stalled = new Promise<unknown>((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
        })
        return { status: 200, ok: true, json: () => stalled } as unknown as Response
      })
    )

    const outcome = client().getStatus().catch((e: unknown) => e)
    await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS + 1)

    const error = await outcome
    expect(error).toBeInstanceOf(NetworkError)
    expect((error as NetworkError).message).toMatch(/did not respond/)
  })

  it('does not leave a timer running after a successful request', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(makeStatus())))

    await client().getStatus()

    expect(vi.getTimerCount()).toBe(0)
  })

  it('reports an unparsable body as an HttpError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>', { status: 200 })))

    await expect(client().getStatus()).rejects.toBeInstanceOf(HttpError)
  })

  it('reports a non-object status as an HttpError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse('nope')))

    await expect(client().getStatus()).rejects.toBeInstanceOf(HttpError)
  })
})

describe('normalizeStatus', () => {
  it('passes a complete status through unchanged', () => {
    const status = makeStatus()

    expect(normalizeStatus(JSON.parse(JSON.stringify(status)))).toEqual(status)
  })

  it('survives missing sections and null fields instead of throwing', () => {
    const status = normalizeStatus({ deviceName: 'Cam', battery: { percent: 50, temperatureC: null } })

    expect(status.deviceName).toBe('Cam')
    expect(status.faces).toEqual([])
    expect(status.backup).toBeNull()
    expect(status.storage).toBeNull()
    expect(status.battery).toEqual({ percent: 50, charging: false, temperatureC: null })
    expect(status.camera).toBe('back')
    expect(status.videoQuality).toBe('1080p')
  })

  it('drops malformed face boxes and falls back to the face count of the list', () => {
    const status = normalizeStatus({
      faces: [{ x: 0.1, y: 0.2, w: 0.3, h: 0.4 }, { x: 'a' }, null, 7]
    })

    expect(status.faces).toEqual([{ x: 0.1, y: 0.2, w: 0.3, h: 0.4 }])
    expect(status.faceCount).toBe(1)
  })

  it('rejects unknown enum values and non-finite numbers', () => {
    const status = normalizeStatus({
      camera: 'sideways',
      videoQuality: '8K',
      recordingSeconds: Number.NaN,
      backup: { state: 'weird', quotaUsedBytes: 'lots', quotaTotalBytes: null }
    })

    expect(status.camera).toBe('back')
    expect(status.videoQuality).toBe('1080p')
    expect(status.recordingSeconds).toBe(0)
    expect(status.backup).toMatchObject({ state: 'disconnected', quotaUsedBytes: null, quotaTotalBytes: null })
  })

  it('keeps an unlimited quota (null total with known usage) intact', () => {
    const status = normalizeStatus({ backup: { state: 'connected', quotaUsedBytes: 123, quotaTotalBytes: null } })

    expect(status.backup).toMatchObject({ quotaUsedBytes: 123, quotaTotalBytes: null })
  })

  it('passes streamState through when the phone sends it', () => {
    expect(normalizeStatus({ streamState: 'live' }).streamState).toBe('live')
    expect('streamState' in normalizeStatus({})).toBe(false)
  })

  it('throws for a payload that is not an object', () => {
    expect(() => normalizeStatus(null)).toThrow()
    expect(() => normalizeStatus([])).toThrow()
  })
})

describe('normalizeEvents', () => {
  it('drops entries that cannot be displayed and fills defaults for the rest', () => {
    const events = normalizeEvents([
      { id: 1, type: 'MOTION', timestamp: 5, title: 'Motion' },
      { id: 'x', type: 'MOTION', timestamp: 5 },
      { id: 2, type: 'MOTION' },
      null
    ])

    expect(events).toEqual([
      { id: 1, type: 'MOTION', title: 'Motion', description: '', timestamp: 5, faceCount: 0, confidence: 0 }
    ])
  })

  it('throws when the payload is not a list', () => {
    expect(() => normalizeEvents({})).toThrow()
  })
})
