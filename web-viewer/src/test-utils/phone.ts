import { vi } from 'vitest'
import { jsonResponse, makeEvent, makeStatus } from './fixtures'
import { whepFetchMock } from './fakeRtc'
import type { Status } from '../lib/api'

/** A saved configuration pointing at a (fake) phone and MediaMTX. */
export const stored = {
  controlUrl: 'http://192.168.1.50:8080',
  token: 'tok',
  whepUrl: 'http://192.168.1.100:8889/perch/whep'
}

/** fetch mock serving the phone control API and the WHEP endpoint. */
export function phoneFetch(status: Status = makeStatus()) {
  const whep = whepFetchMock()
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith(`${stored.controlUrl}/api/events`)) return jsonResponse([makeEvent({ title: 'Hall motion' })])
    if (url.startsWith(stored.controlUrl)) return jsonResponse(status)
    return whep(url, init)
  })
}

type FetchCall = [string, RequestInit | undefined]

export function urlsOf(fetchMock: ReturnType<typeof vi.fn>): string[] {
  return (fetchMock.mock.calls as unknown as FetchCall[]).map(([url]) => url)
}

/** The Authorization headers sent to URLs starting with `urlPrefix`. */
export function authorizationFor(fetchMock: ReturnType<typeof vi.fn>, urlPrefix: string): string[] {
  return (fetchMock.mock.calls as unknown as FetchCall[])
    .filter(([url]) => url.startsWith(urlPrefix))
    .map(([, init]) => (init?.headers as Record<string, string> | undefined)?.Authorization ?? '')
}
