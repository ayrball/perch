import { describe, it, expect, vi } from 'vitest'
import {
  WhepError,
  backoffDelay,
  waitForIceGathering,
  whepConnect,
  whepDisconnect
} from '../lib/whep'
import { readInboundVideo, toStreamStats } from '../lib/rtcStats'
import { containRect } from '../lib/geometry'
import { FakePeerConnection, asPeerConnection, whepFetchMock } from '../test-utils/fakeRtc'

const WHEP_URL = 'http://192.168.1.100:8889/perch/whep'

describe('whepConnect', () => {
  it('POSTs the SDP offer and applies the answer', async () => {
    const fetchMock = whepFetchMock({ location: '/perch/whep/session-1' })
    vi.stubGlobal('fetch', fetchMock)
    const pc = new FakePeerConnection()

    const session = await whepConnect(asPeerConnection(pc), WHEP_URL)

    expect(pc.transceivers).toEqual([
      { kind: 'video', direction: 'recvonly' },
      { kind: 'audio', direction: 'recvonly' }
    ])
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(WHEP_URL)
    expect(init?.method).toBe('POST')
    expect((init?.headers as Record<string, string>)['Content-Type']).toBe('application/sdp')
    expect(init?.body).toBe('v=0 offer-sdp')
    expect(pc.remoteDescription).toEqual({ type: 'answer', sdp: 'v=0 answer-sdp' })
    expect(session.resourceUrl).toBe('http://192.168.1.100:8889/perch/whep/session-1')
  })

  it('keeps an absolute Location as is, and tolerates a missing one', async () => {
    vi.stubGlobal('fetch', whepFetchMock({ location: 'http://other:8889/res/1' }))
    const absolute = await whepConnect(asPeerConnection(new FakePeerConnection()), WHEP_URL)
    expect(absolute.resourceUrl).toBe('http://other:8889/res/1')

    vi.stubGlobal('fetch', vi.fn(async () => new Response('answer', { status: 201 })))
    const missing = await whepConnect(asPeerConnection(new FakePeerConnection()), WHEP_URL)
    expect(missing.resourceUrl).toBeNull()
  })

  it('treats an unparsable Location as no session resource instead of throwing', async () => {
    vi.stubGlobal('fetch', whepFetchMock({ location: 'http://[bad' }))
    const pc = new FakePeerConnection()

    const session = await whepConnect(asPeerConnection(pc), WHEP_URL)

    expect(session.resourceUrl).toBeNull()
    expect(pc.remoteDescription).toEqual({ type: 'answer', sdp: 'v=0 answer-sdp' })
  })

  it('fails on a non-201 response', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('nope', { status: 404 })))

    await expect(whepConnect(asPeerConnection(new FakePeerConnection()), WHEP_URL)).rejects.toThrow(
      'Stream server returned HTTP 404.'
    )
  })

  it('fails with a WhepError when the server is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))

    await expect(
      whepConnect(asPeerConnection(new FakePeerConnection()), WHEP_URL)
    ).rejects.toBeInstanceOf(WhepError)
  })

  it('releases the server session when the answer cannot be applied', async () => {
    const fetchMock = whepFetchMock({ location: '/perch/whep/session-9' })
    vi.stubGlobal('fetch', fetchMock)
    const pc = new FakePeerConnection()
    pc.setRemoteDescription = async () => {
      throw new Error('bad sdp')
    }

    await expect(whepConnect(asPeerConnection(pc), WHEP_URL)).rejects.toBeInstanceOf(WhepError)

    const deleteCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'DELETE')
    expect(deleteCall?.[0]).toBe('http://192.168.1.100:8889/perch/whep/session-9')
  })
})

describe('whepDisconnect', () => {
  it('sends DELETE to the session resource and never throws', async () => {
    const fetchMock = whepFetchMock()
    vi.stubGlobal('fetch', fetchMock)

    await whepDisconnect('http://192.168.1.100:8889/perch/whep/session-1')

    expect(fetchMock).toHaveBeenCalledWith(
      'http://192.168.1.100:8889/perch/whep/session-1',
      expect.objectContaining({ method: 'DELETE' })
    )

    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new Error('offline'))))
    await expect(whepDisconnect('http://x/y')).resolves.toBeUndefined()
  })
})

describe('waitForIceGathering', () => {
  it('resolves immediately when gathering is complete', async () => {
    const pc = new FakePeerConnection()
    await expect(waitForIceGathering(asPeerConnection(pc), 2000)).resolves.toBeUndefined()
  })

  it('resolves when gathering completes', async () => {
    const pc = new FakePeerConnection()
    pc.iceGatheringState = 'gathering'
    const done = vi.fn()
    const waiting = waitForIceGathering(asPeerConnection(pc), 2000).then(done)

    await Promise.resolve()
    expect(done).not.toHaveBeenCalled()
    pc.iceGatheringState = 'complete'
    pc.dispatchEvent(new Event('icegatheringstatechange'))
    await waiting

    expect(done).toHaveBeenCalled()
  })

  it('gives up after the timeout', async () => {
    vi.useFakeTimers()
    const pc = new FakePeerConnection()
    pc.iceGatheringState = 'gathering'
    const done = vi.fn()
    void waitForIceGathering(asPeerConnection(pc), 2000).then(done)

    await vi.advanceTimersByTimeAsync(1999)
    expect(done).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(2)
    expect(done).toHaveBeenCalled()
  })
})

describe('backoffDelay', () => {
  it('doubles from 1s up to 15s', () => {
    expect([0, 1, 2, 3, 4, 5, 10].map(backoffDelay)).toEqual([1000, 2000, 4000, 8000, 15000, 15000, 15000])
  })
})

describe('rtcStats', () => {
  const report = (bytes: number, timestamp: number) =>
    new Map<string, Record<string, unknown>>([
      ['a', { type: 'inbound-rtp', kind: 'audio', bytesReceived: 999, timestamp }],
      [
        'v',
        {
          type: 'inbound-rtp',
          kind: 'video',
          bytesReceived: bytes,
          timestamp,
          frameWidth: 1920,
          frameHeight: 1080,
          framesPerSecond: 29.7
        }
      ]
    ]) as unknown as RTCStatsReport

  it('reads the inbound video sample', () => {
    expect(readInboundVideo(report(1000, 5000))).toEqual({
      bytesReceived: 1000,
      timestamp: 5000,
      width: 1920,
      height: 1080,
      fps: 29.7
    })
    expect(readInboundVideo(new Map() as unknown as RTCStatsReport)).toBeNull()
  })

  it('derives bitrate from consecutive samples', () => {
    const first = readInboundVideo(report(0, 1000))!
    const second = readInboundVideo(report(500_000, 3000))!

    expect(toStreamStats(first, null).bitrateBps).toBeNull()
    expect(toStreamStats(second, first)).toEqual({
      bitrateBps: 2_000_000,
      width: 1920,
      height: 1080,
      fps: 29.7
    })
  })
})

describe('containRect', () => {
  it('letterboxes a portrait video inside a landscape box', () => {
    const rect = containRect(1600, 900, 1080, 1920)
    expect(rect.height).toBe(900)
    expect(rect.width).toBeCloseTo(506.25)
    expect(rect.left).toBeCloseTo((1600 - 506.25) / 2)
    expect(rect.top).toBe(0)
  })

  it('fills the box when aspect ratios match and falls back when sizes are unknown', () => {
    expect(containRect(1600, 900, 1920, 1080)).toEqual({ left: 0, top: 0, width: 1600, height: 900 })
    expect(containRect(1600, 900, 0, 0)).toEqual({ left: 0, top: 0, width: 1600, height: 900 })
  })
})
