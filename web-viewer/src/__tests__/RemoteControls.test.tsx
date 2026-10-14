import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { RemoteControls } from '../components/RemoteControls'
import { PerchClient, type Status } from '../lib/api'
import type { Connection } from '../hooks/usePerchStatus'
import { jsonResponse, makeBackup, makeStatus } from '../test-utils/fixtures'

type FetchCall = [string, RequestInit]

function setup(options: { status?: Status | null; connection?: Connection; respondWith?: Response } = {}) {
  const { status = makeStatus(), connection = 'online' } = options
  const fetchMock = vi.fn(async () => options.respondWith ?? jsonResponse(makeStatus()))
  vi.stubGlobal('fetch', fetchMock)
  const onStatus = vi.fn()
  const client = new PerchClient({ controlUrl: 'http://phone:8080', token: 't' })
  render(<RemoteControls client={client} status={status} connection={connection} onStatus={onStatus} />)
  const calls = () => fetchMock.mock.calls as unknown as FetchCall[]
  return { fetchMock, onStatus, calls }
}

describe('RemoteControls commands', () => {
  it('turns the flashlight on through the API and reports the server status', async () => {
    const answer = makeStatus({ flashlight: true })
    const { calls, onStatus } = setup({ respondWith: jsonResponse(answer) })

    fireEvent.click(screen.getByRole('button', { name: /flashlight/i }))

    await waitFor(() => expect(onStatus).toHaveBeenCalledWith(answer))
    expect(calls()[0][0]).toBe('http://phone:8080/api/flashlight')
    expect(calls()[0][1].body).toBe('{"on":true}')
  })

  it('shows the flashlight as on while the request is in flight', async () => {
    let release: (response: Response) => void = () => undefined
    const fetchMock = vi.fn(() => new Promise<Response>(resolve => (release = resolve)))
    vi.stubGlobal('fetch', fetchMock)
    const client = new PerchClient({ controlUrl: 'http://phone:8080', token: 't' })
    render(<RemoteControls client={client} status={makeStatus()} connection="online" onStatus={vi.fn()} />)

    const button = screen.getByRole('button', { name: /flashlight/i })
    fireEvent.click(button)

    await waitFor(() => expect(button.getAttribute('aria-pressed')).toBe('true'))
    release(jsonResponse(makeStatus({ flashlight: false })))
    await waitFor(() => expect(button.getAttribute('aria-pressed')).toBe('false'))
  })

  it('starts the siren for 10 seconds', async () => {
    const { calls, onStatus } = setup()

    fireEvent.click(screen.getByRole('button', { name: /sound siren/i }))

    await waitFor(() => expect(onStatus).toHaveBeenCalled())
    expect(calls()[0][0]).toBe('http://phone:8080/api/siren')
    expect(calls()[0][1].body).toBe('{"seconds":10}')
  })

  it('offers to stop an active siren', async () => {
    const { calls, onStatus } = setup({ status: makeStatus({ sirenActive: true }) })

    fireEvent.click(screen.getByRole('button', { name: /stop siren/i }))

    await waitFor(() => expect(onStatus).toHaveBeenCalled())
    expect(calls()[0][0]).toBe('http://phone:8080/api/siren/stop')
  })

  it('flips the camera', async () => {
    const { calls, onStatus } = setup()

    fireEvent.click(screen.getByRole('button', { name: /flip camera/i }))

    await waitFor(() => expect(onStatus).toHaveBeenCalled())
    expect(calls()[0][0]).toBe('http://phone:8080/api/camera/flip')
  })

  it('changes the video quality', async () => {
    const { calls, onStatus } = setup()

    const select = screen.getByRole('combobox', { name: /video quality/i }) as HTMLSelectElement
    expect(select.value).toBe('1080p')
    fireEvent.change(select, { target: { value: '720p' } })

    await waitFor(() => expect(onStatus).toHaveBeenCalled())
    expect(calls()[0][0]).toBe('http://phone:8080/api/quality')
    expect(calls()[0][1].body).toBe('{"quality":"720p"}')
  })

  it('shows API errors inline without alert()', async () => {
    const alertSpy = vi.fn()
    vi.stubGlobal('alert', alertSpy)
    const { onStatus } = setup({ respondWith: jsonResponse({ error: 'Camera busy' }, 409) })

    fireEvent.click(screen.getByRole('button', { name: /flip camera/i }))

    expect((await screen.findByRole('alert')).textContent).toBe('Camera busy')
    expect(onStatus).not.toHaveBeenCalled()
    expect(alertSpy).not.toHaveBeenCalled()
  })

  it('reverts the optimistic state when the command fails', async () => {
    setup({ respondWith: jsonResponse({ error: 'No flash' }, 500) })
    const button = screen.getByRole('button', { name: /flashlight/i })

    fireEvent.click(button)

    await screen.findByRole('alert')
    expect(button.getAttribute('aria-pressed')).toBe('false')
  })
})

describe('RemoteControls availability', () => {
  it.each<[Connection, RegExp]>([
    ['rate_limited', /wait a minute/i],
    ['unconfigured', /settings/i],
    ['offline', /offline/i],
    ['unauthorized', /token/i]
  ])('disables controls with a tooltip when %s', (connection, reason) => {
    const { fetchMock } = setup({ connection, status: null })

    const button = screen.getByRole('button', { name: /flashlight/i }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    expect(button.title).toMatch(reason)
    expect((screen.getByRole('combobox', { name: /video quality/i }) as HTMLSelectElement).disabled).toBe(true)

    fireEvent.click(button)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('RemoteControls cloud backup card', () => {
  it('shows connected backup details and the quota bar', () => {
    setup()

    expect(screen.getByText('CONNECTED')).toBeDefined()
    expect(screen.getByText('owner@example.com')).toBeDefined()
    expect(screen.getByText('Auto-sync every 15 min')).toBeDefined()
    expect(screen.getByText('2 pending / 40 uploaded')).toBeDefined()
    expect(screen.getByText('4.0 GB / 16.0 GB')).toBeDefined()
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('25')
  })

  it('says quota is unavailable when the phone cannot tell', () => {
    setup({ status: makeStatus({ backup: makeBackup({ quotaUsedBytes: null, quotaTotalBytes: null }) }) })

    expect(screen.getByText('Quota unavailable')).toBeDefined()
    expect(screen.queryByRole('progressbar')).toBeNull()
  })

  it('shows an unlimited Drive quota as used / Unlimited, without a bar', () => {
    setup({ status: makeStatus({ backup: makeBackup({ quotaUsedBytes: 5 * 1024 ** 3, quotaTotalBytes: null }) }) })

    expect(screen.getByText('5.0 GB / Unlimited')).toBeDefined()
    expect(screen.queryByText('Quota unavailable')).toBeNull()
    expect(screen.queryByRole('progressbar')).toBeNull()
  })

  it('says quota is unavailable when only the total is known', () => {
    setup({ status: makeStatus({ backup: makeBackup({ quotaUsedBytes: null, quotaTotalBytes: 16 * 1024 ** 3 }) }) })

    expect(screen.getByText('Quota unavailable')).toBeDefined()
  })

  it.each([
    ['needs_auth', 'NEEDS SIGN-IN'],
    ['disconnected', 'NOT CONNECTED']
  ] as const)('labels the %s state', (state, label) => {
    setup({ status: makeStatus({ backup: makeBackup({ state }) }) })

    expect(screen.getByText(label)).toBeDefined()
  })

  it('shows phone storage usage', () => {
    setup()

    expect(screen.getByText('2.0 GB / 8.0 GB')).toBeDefined()
    expect(screen.getByText('20.0 GB free on device')).toBeDefined()
  })

  it('warns when the phone is throttled', () => {
    setup({ status: makeStatus({ throttled: true }) })

    expect(screen.getByText(/reduced quality/i)).toBeDefined()
  })
})

describe('RemoteControls stale responses', () => {
  it('ignores a response that arrives after the connection was switched to another phone', async () => {
    let release: (response: Response) => void = () => undefined
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => (release = resolve))))
    const onStatus = vi.fn()
    const oldPhone = new PerchClient({ controlUrl: 'http://old-phone:8080', token: 'a' })
    const newPhone = new PerchClient({ controlUrl: 'http://new-phone:8080', token: 'b' })
    const view = render(<RemoteControls client={oldPhone} status={makeStatus()} connection="online" onStatus={onStatus} />)

    fireEvent.click(screen.getByRole('button', { name: /flip camera/i }))
    view.rerender(<RemoteControls client={newPhone} status={makeStatus()} connection="online" onStatus={onStatus} />)
    release(jsonResponse(makeStatus({ deviceName: 'Old phone' })))

    await waitFor(() =>
      expect((screen.getByRole('button', { name: /flip camera/i }) as HTMLButtonElement).disabled).toBe(false)
    )
    expect(onStatus).not.toHaveBeenCalled()
  })

  it('does not show an old phone error after the connection changed', async () => {
    let fail: (error: Error) => void = () => undefined
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((_resolve, reject) => (fail = reject))))
    const oldPhone = new PerchClient({ controlUrl: 'http://old-phone:8080', token: 'a' })
    const newPhone = new PerchClient({ controlUrl: 'http://new-phone:8080', token: 'b' })
    const view = render(<RemoteControls client={oldPhone} status={makeStatus()} connection="online" onStatus={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: /flip camera/i }))
    view.rerender(<RemoteControls client={newPhone} status={makeStatus()} connection="online" onStatus={vi.fn()} />)
    fail(new TypeError('Failed to fetch'))

    await waitFor(() =>
      expect((screen.getByRole('button', { name: /flip camera/i }) as HTMLButtonElement).disabled).toBe(false)
    )
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('RemoteControls delayed quality change', () => {
  const hint = /applies from the next recording segment/i

  it('keeps the chosen quality and explains the delay while the phone still reports the old one', async () => {
    const stillOld = makeStatus({ videoQuality: '1080p' })
    const onStatus = vi.fn()
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(stillOld)))
    const client = new PerchClient({ controlUrl: 'http://phone:8080', token: 't' })
    const view = render(<RemoteControls client={client} status={stillOld} connection="online" onStatus={onStatus} />)
    const select = screen.getByRole('combobox', { name: /video quality/i }) as HTMLSelectElement

    fireEvent.change(select, { target: { value: '720p' } })

    await waitFor(() => expect(onStatus).toHaveBeenCalled())
    view.rerender(<RemoteControls client={client} status={stillOld} connection="online" onStatus={onStatus} />)
    expect(select.value).toBe('720p')
    expect(screen.getByText(hint)).toBeDefined()

    view.rerender(
      <RemoteControls client={client} status={makeStatus({ videoQuality: '720p' })} connection="online" onStatus={onStatus} />
    )
    expect(select.value).toBe('720p')
    expect(screen.queryByText(hint)).toBeNull()
  })

  it('stops overriding the phone once it reports a different quality', async () => {
    const stillOld = makeStatus({ videoQuality: '1080p' })
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(stillOld)))
    const client = new PerchClient({ controlUrl: 'http://phone:8080', token: 't' })
    const onStatus = vi.fn()
    const view = render(<RemoteControls client={client} status={stillOld} connection="online" onStatus={onStatus} />)
    fireEvent.change(screen.getByRole('combobox', { name: /video quality/i }), { target: { value: '720p' } })
    await waitFor(() => expect(onStatus).toHaveBeenCalled())

    view.rerender(
      <RemoteControls client={client} status={makeStatus({ videoQuality: '4K' })} connection="online" onStatus={onStatus} />
    )

    expect((screen.getByRole('combobox', { name: /video quality/i }) as HTMLSelectElement).value).toBe('4K')
    expect(screen.queryByText(hint)).toBeNull()
  })

  it('shows no hint when the phone applied the change immediately', async () => {
    const applied = makeStatus({ videoQuality: '720p' })
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(applied)))
    const client = new PerchClient({ controlUrl: 'http://phone:8080', token: 't' })
    const onStatus = vi.fn()
    render(<RemoteControls client={client} status={makeStatus()} connection="online" onStatus={onStatus} />)

    fireEvent.change(screen.getByRole('combobox', { name: /video quality/i }), { target: { value: '720p' } })

    await waitFor(() => expect(onStatus).toHaveBeenCalledWith(applied))
    expect(screen.queryByText(hint)).toBeNull()
  })
})
