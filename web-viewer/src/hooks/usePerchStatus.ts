import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  PerchClient,
  RateLimitedError,
  UnauthorizedError,
  describeError,
  type PerchEvent,
  type Status,
} from '../lib/api'
import { isConfigured, type PerchConfig } from '../lib/config'

export type Connection =
  | 'unconfigured'
  | 'connecting'
  | 'online'
  | 'unauthorized'
  | 'rate_limited'
  | 'offline'

export const STATUS_POLL_MS = 2000
export const EVENTS_POLL_MS = 5000
/** How long to leave the phone alone after it answers 429 (its lockout window is one minute). */
export const RATE_LIMIT_PAUSE_MS = 60_000

export interface PerchState {
  client: PerchClient | null
  status: Status | null
  events: PerchEvent[]
  connection: Connection
  error: string | null
  /** Fetches status and events immediately (also resumes polling after an auth failure). */
  refresh: () => void
  /** Adopts a status returned by a control call so the UI does not wait for the next poll. */
  applyStatus: (status: Status) => void
}

export function usePerchStatus(config: PerchConfig): PerchState {
  const { controlUrl, token } = config
  const configured = isConfigured(config)

  const client = useMemo(
    () => (configured ? new PerchClient({ controlUrl, token }) : null),
    [configured, controlUrl, token],
  )

  const [status, setStatus] = useState<Status | null>(null)
  const [events, setEvents] = useState<PerchEvent[]>([])
  const [connection, setConnection] = useState<Connection>('unconfigured')
  const [error, setError] = useState<string | null>(null)
  const pollNowRef = useRef<() => void>(() => {})
  /** Bumped whenever a command result is adopted, so older in-flight polls cannot overwrite it. */
  const adoptedRef = useRef(0)

  useEffect(() => {
    pollNowRef.current = () => {}
    setStatus(null)
    setEvents([])
    setError(null)
    setConnection(client ? 'connecting' : 'unconfigured')
    if (!client) return

    let active = true
    let rejected = false
    let pausedUntil = 0
    let statusInFlight = false
    let eventsInFlight = false
    let eventsTimer: ReturnType<typeof setInterval> | undefined

    const blocked = () => rejected || Date.now() < pausedUntil

    /** Returns true when the error was handled as an auth or rate-limit problem. */
    const handleAccessError = (err: unknown): boolean => {
      if (err instanceof UnauthorizedError) {
        rejected = true
        setConnection('unauthorized')
      } else if (err instanceof RateLimitedError) {
        pausedUntil = Date.now() + RATE_LIMIT_PAUSE_MS
        setConnection('rate_limited')
      } else {
        return false
      }
      setError(describeError(err))
      return true
    }

    const pollEvents = async () => {
      if (eventsInFlight || blocked()) return
      eventsInFlight = true
      try {
        const next = await client.getEvents()
        if (active) setEvents(next)
      } catch (err) {
        // Other failures are reported through status polling; keep the last good events.
        if (active) handleAccessError(err)
      } finally {
        eventsInFlight = false
      }
    }

    const startEvents = () => {
      if (eventsTimer !== undefined) return
      void pollEvents()
      eventsTimer = setInterval(() => void pollEvents(), EVENTS_POLL_MS)
    }

    const pollStatus = async () => {
      if (statusInFlight || blocked()) return
      statusInFlight = true
      const adopted = adoptedRef.current
      try {
        const next = await client.getStatus()
        // A command result that arrived while this poll was in flight is newer.
        if (!active || adopted !== adoptedRef.current) return
        setStatus(next)
        setConnection('online')
        setError(null)
        // Events wait for the first good status so a bad token costs one auth attempt, not two.
        startEvents()
      } catch (err) {
        if (!active) return
        if (!handleAccessError(err)) {
          setConnection('offline')
          setError(describeError(err))
        }
      } finally {
        statusInFlight = false
      }
    }

    pollNowRef.current = () => {
      rejected = false
      pausedUntil = 0
      void pollStatus()
      if (eventsTimer !== undefined) void pollEvents()
    }

    void pollStatus()
    const statusTimer = setInterval(() => void pollStatus(), STATUS_POLL_MS)

    return () => {
      active = false
      pollNowRef.current = () => {}
      clearInterval(statusTimer)
      clearInterval(eventsTimer)
    }
  }, [client])

  const refresh = useCallback(() => pollNowRef.current(), [])
  const applyStatus = useCallback((next: Status) => {
    adoptedRef.current++
    setStatus(next)
    setConnection('online')
    setError(null)
  }, [])

  return { client, status, events, connection, error, refresh, applyStatus }
}
