export const ICE_GATHER_TIMEOUT_MS = 2000
const BACKOFF_BASE_MS = 1000
const BACKOFF_MAX_MS = 15000

export class WhepError extends Error {}

export function describeWhepError(error: unknown): string {
  return error instanceof WhepError ? error.message : 'Could not start the stream.'
}

/** 1s, 2s, 4s, 8s, then 15s. `attempt` is the number of failures so far, starting at 0. */
export function backoffDelay(attempt: number): number {
  return Math.min(BACKOFF_BASE_MS * 2 ** attempt, BACKOFF_MAX_MS)
}

/** Resolves once ICE gathering completes, or after `timeoutMs`, whichever comes first. */
export function waitForIceGathering(pc: RTCPeerConnection, timeoutMs: number): Promise<void> {
  if (pc.iceGatheringState === 'complete') return Promise.resolve()
  return new Promise(resolve => {
    const finish = () => {
      clearTimeout(timer)
      pc.removeEventListener('icegatheringstatechange', onChange)
      resolve()
    }
    const onChange = () => {
      if (pc.iceGatheringState === 'complete') finish()
    }
    const timer = setTimeout(finish, timeoutMs)
    pc.addEventListener('icegatheringstatechange', onChange)
  })
}

export interface WhepSession {
  /** Absolute URL of the session resource to DELETE on teardown, if the server provided one. */
  resourceUrl: string | null
}

/** Resolves the session resource, treating a missing or unparsable Location as "no resource". */
function resolveResource(location: string | null, base: string): string | null {
  if (!location) return null
  try {
    return new URL(location, base).toString()
  } catch {
    return null
  }
}

/**
 * Negotiates a receive-only WHEP session (non-trickle ICE) on `pc`:
 * POST the SDP offer, expect 201 with the answer SDP and a Location header.
 */
export async function whepConnect(
  pc: RTCPeerConnection,
  whepUrl: string,
  options: { iceTimeoutMs?: number; signal?: AbortSignal } = {},
): Promise<WhepSession> {
  pc.addTransceiver('video', { direction: 'recvonly' })
  pc.addTransceiver('audio', { direction: 'recvonly' })

  await pc.setLocalDescription(await pc.createOffer())
  await waitForIceGathering(pc, options.iceTimeoutMs ?? ICE_GATHER_TIMEOUT_MS)

  const offerSdp = pc.localDescription?.sdp
  if (!offerSdp) throw new WhepError('Could not create a WebRTC offer.')

  let response: Response
  try {
    response = await fetch(whepUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/sdp' },
      body: offerSdp,
      signal: options.signal,
    })
  } catch (error) {
    if (options.signal?.aborted) throw error
    throw new WhepError('Cannot reach the stream server.')
  }

  if (response.status !== 201) {
    throw new WhepError(`Stream server returned HTTP ${response.status}.`)
  }

  const resourceUrl = resolveResource(response.headers.get('Location'), whepUrl)

  try {
    const answerSdp = await response.text()
    await pc.setRemoteDescription({ type: 'answer', sdp: answerSdp })
  } catch {
    if (resourceUrl) await whepDisconnect(resourceUrl)
    throw new WhepError('The stream server sent an invalid answer.')
  }

  return { resourceUrl }
}

/** Best-effort DELETE of the WHEP session resource. */
export async function whepDisconnect(resourceUrl: string): Promise<void> {
  try {
    await fetch(resourceUrl, { method: 'DELETE', keepalive: true })
  } catch {
    // The server expires abandoned sessions on its own.
  }
}
