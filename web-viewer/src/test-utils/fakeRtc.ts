import { vi } from 'vitest'

export class FakeMediaStream {
  readonly tracks: unknown[] = []

  addTrack(track: unknown): void {
    this.tracks.push(track)
  }
}

interface Deferred {
  resolve: () => void
  reject: (error: Error) => void
}

type Description = { type: string; sdp: string }

/**
 * A stand-in for RTCPeerConnection. Like the real one, close() cancels pending
 * negotiation steps (their promises reject) and later calls fail.
 * Set `holdOffer` / `holdAnswer` before negotiation to pause a step until
 * `releaseOffer()` / `releaseAnswer()`.
 */
export class FakePeerConnection extends EventTarget {
  static instances: FakePeerConnection[] = []
  /** Pause every new connection's createOffer / setRemoteDescription until released. */
  static holdOffer = false
  static holdAnswer = false

  iceGatheringState: RTCIceGatheringState = 'complete'
  connectionState: RTCPeerConnectionState = 'new'
  localDescription: Description | null = null
  remoteDescription: Description | null = null
  transceivers: { kind: string; direction?: string }[] = []
  closed = false
  stats = new Map<string, Record<string, unknown>>()
  onconnectionstatechange: (() => void) | null = null
  ontrack: ((event: { track: unknown }) => void) | null = null

  private pendingOffer: Deferred | null = null
  private pendingAnswer: Deferred | null = null

  constructor() {
    super()
    FakePeerConnection.instances.push(this)
  }

  addTransceiver(kind: string, init?: { direction?: string }): void {
    this.transceivers.push({ kind, direction: init?.direction })
  }

  async createOffer(): Promise<Description> {
    this.assertOpen()
    if (FakePeerConnection.holdOffer) {
      await new Promise<void>((resolve, reject) => {
        this.pendingOffer = { resolve, reject }
      })
    }
    return { type: 'offer', sdp: 'v=0 offer-sdp' }
  }

  async setLocalDescription(description: Description): Promise<void> {
    this.assertOpen()
    this.localDescription = description
  }

  async setRemoteDescription(description: Description): Promise<void> {
    this.assertOpen()
    if (FakePeerConnection.holdAnswer) {
      await new Promise<void>((resolve, reject) => {
        this.pendingAnswer = { resolve, reject }
      })
    }
    this.remoteDescription = description
  }

  async getStats(): Promise<Map<string, Record<string, unknown>>> {
    return this.stats
  }

  close(): void {
    this.closed = true
    const error = new DOMException('The peer connection is closed.', 'InvalidStateError')
    this.pendingOffer?.reject(error)
    this.pendingAnswer?.reject(error)
    this.pendingOffer = null
    this.pendingAnswer = null
  }

  releaseOffer(): void {
    this.pendingOffer?.resolve()
    this.pendingOffer = null
  }

  releaseAnswer(): void {
    this.pendingAnswer?.resolve()
    this.pendingAnswer = null
  }

  /** Test helper: change the connection state and fire the handler like a browser would. */
  simulateState(state: RTCPeerConnectionState): void {
    this.connectionState = state
    this.onconnectionstatechange?.()
  }

  simulateTrack(): void {
    this.ontrack?.({ track: { kind: 'video' } })
  }

  private assertOpen(): void {
    if (this.closed) throw new DOMException('The peer connection is closed.', 'InvalidStateError')
  }
}

export function installFakeRtc(): void {
  FakePeerConnection.instances = []
  FakePeerConnection.holdOffer = false
  FakePeerConnection.holdAnswer = false
  vi.stubGlobal('RTCPeerConnection', FakePeerConnection)
  vi.stubGlobal('MediaStream', FakeMediaStream)
}

export function asPeerConnection(fake: FakePeerConnection): RTCPeerConnection {
  return fake as unknown as RTCPeerConnection
}

interface WhepFetchOptions {
  location?: string
  /** Keep the POST pending until `release()` is called (or the request is aborted). */
  hold?: boolean
}

/**
 * A fetch mock answering WHEP POSTs with 201 + Location and DELETEs with 200.
 * Like real fetch it rejects with an AbortError when the request's signal aborts.
 */
export function whepFetchMock({ location = '/perch/whep/session-1', hold = false }: WhepFetchOptions = {}) {
  const waiting: (() => void)[] = []
  const answer = () => new Response('v=0 answer-sdp', { status: 201, headers: { Location: location } })

  const mock = vi.fn(async (_url: string, init?: RequestInit): Promise<Response> => {
    if (init?.method === 'DELETE') return new Response(null, { status: 200 })
    const signal = init?.signal
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
    if (!hold) return answer()
    return new Promise<Response>((resolve, reject) => {
      signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      waiting.push(() => resolve(answer()))
    })
  })

  return Object.assign(mock, {
    release: () => waiting.splice(0).forEach(resolve => resolve()),
  })
}
