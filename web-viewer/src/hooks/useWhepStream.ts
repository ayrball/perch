import { useEffect, useState } from 'react'
import { readInboundVideo, toStreamStats, type InboundVideoSample, type StreamStats } from '../lib/rtcStats'
import { backoffDelay, describeWhepError, whepConnect, whepDisconnect } from '../lib/whep'

export type StreamState = 'unconfigured' | 'connecting' | 'live' | 'reconnecting'

export const STATS_POLL_MS = 2000
/** ICE "disconnected" is often transient (Wi-Fi roam, brief loss); give it time to recover. */
export const DISCONNECT_GRACE_MS = 4000

export interface WhepStream {
  stream: MediaStream | null
  state: StreamState
  stats: StreamStats | null
  error: string | null
}

export function useWhepStream(whepUrl: string | null): WhepStream {
  const [stream, setStream] = useState<MediaStream | null>(null)
  const [state, setState] = useState<StreamState>(whepUrl ? 'connecting' : 'unconfigured')
  const [stats, setStats] = useState<StreamStats | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setStream(null)
    setStats(null)
    setError(null)
    if (!whepUrl) {
      setState('unconfigured')
      return
    }
    setState('connecting')

    let stopped = false
    let failures = 0
    let retryTimer: ReturnType<typeof setTimeout> | undefined
    let teardownCurrent: () => void = () => {}
    let failCurrent: (message: string) => void = () => {}

    const connect = () => {
      const pc = new RTCPeerConnection()
      const media = new MediaStream()
      const abort = new AbortController()
      let resourceUrl: string | null = null
      let statsTimer: ReturnType<typeof setInterval> | undefined
      let graceTimer: ReturnType<typeof setTimeout> | undefined
      let previousSample: InboundVideoSample | null = null
      let finished = false

      const teardown = () => {
        if (finished) return
        finished = true
        abort.abort()
        clearInterval(statsTimer)
        clearTimeout(graceTimer)
        pc.onconnectionstatechange = null
        pc.ontrack = null
        pc.close()
        if (resourceUrl) void whepDisconnect(resourceUrl)
      }

      const fail = (message: string) => {
        if (finished || stopped) return
        teardown()
        setStream(null)
        setStats(null)
        setError(message)
        setState('reconnecting')
        retryTimer = setTimeout(connect, backoffDelay(failures))
        failures++
      }

      teardownCurrent = teardown
      failCurrent = fail

      const pollStats = async () => {
        try {
          const sample = readInboundVideo(await pc.getStats())
          if (finished || !sample) return
          setStats(toStreamStats(sample, previousSample))
          previousSample = sample
        } catch {
          // Stats are informational; ignore transient failures.
        }
      }

      pc.ontrack = event => {
        media.addTrack(event.track)
        setStream(media)
      }

      pc.onconnectionstatechange = () => {
        const next = pc.connectionState
        if (next === 'connected') {
          clearTimeout(graceTimer)
          graceTimer = undefined
          failures = 0
          setError(null)
          setState('live')
          statsTimer ??= setInterval(() => void pollStats(), STATS_POLL_MS)
        } else if (next === 'disconnected') {
          graceTimer ??= setTimeout(() => fail('The stream connection was lost.'), DISCONNECT_GRACE_MS)
        } else if (next === 'failed' || next === 'closed') {
          fail('The stream connection was lost.')
        }
      }

      whepConnect(pc, whepUrl, { signal: abort.signal })
        .then(session => {
          resourceUrl = session.resourceUrl
          // The page may have been torn down while the answer was in flight.
          if (finished && resourceUrl) void whepDisconnect(resourceUrl)
        })
        .catch(err => fail(describeWhepError(err)))
    }

    // Closing the tab must end the server-side session; the DELETE uses fetch keepalive.
    const onPageHide = () => failCurrent('The page was hidden.')
    window.addEventListener('pagehide', onPageHide)

    connect()

    return () => {
      stopped = true
      window.removeEventListener('pagehide', onPageHide)
      clearTimeout(retryTimer)
      teardownCurrent()
    }
  }, [whepUrl])

  return { stream, state, stats, error }
}
