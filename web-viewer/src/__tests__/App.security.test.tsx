import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { App } from '../App'
import { STORAGE_KEY } from '../lib/config'
import { jsonResponse, makeStatus } from '../test-utils/fixtures'
import { installFakeRtc } from '../test-utils/fakeRtc'
import { authorizationFor, phoneFetch, stored, urlsOf } from '../test-utils/phone'

beforeEach(() => {
  installFakeRtc()
})

describe('App link overrides (security)', () => {
  beforeEach(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored))
  })

  it('never sends the saved token to a different origin named in the URL', async () => {
    window.history.replaceState(null, '', '/?control=http://evil.example&whep=http://evil.example/whep')
    const fetchMock = phoneFetch()
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    await act(async () => {
      await new Promise(resolve => setTimeout(resolve, 50))
    })

    // Nothing is contacted until the user confirms, and certainly not with the saved token.
    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog', { name: /connection settings/i })).toBeDefined()
    expect((screen.getByLabelText('Phone control URL') as HTMLInputElement).value).toBe('http://evil.example')
    expect((screen.getByLabelText('Access token') as HTMLInputElement).value).toBe('')
    expect(screen.getByText(/came from a link/i)).toBeDefined()
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}')).toEqual(stored)
  })

  it('removes the overrides from the address bar after loading', () => {
    window.history.replaceState(null, '', '/?control=http://evil.example&x=1#whep=http://evil.example/whep')
    vi.stubGlobal('fetch', phoneFetch())

    render(<App />)

    expect(window.location.search).toBe('?x=1')
    expect(window.location.hash).toBe('')
  })

  it('keeps using the saved phone when the user cancels the link prompt', async () => {
    window.history.replaceState(null, '', '/?control=http://evil.example')
    const fetchMock = phoneFetch()
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)

    fireEvent.click(screen.getByRole('button', { name: /close settings/i }))

    expect(await screen.findByText('SYSTEM ONLINE')).toBeDefined()
    expect(urlsOf(fetchMock).some(url => url.includes('evil.example'))).toBe(false)
    expect(new Set(authorizationFor(fetchMock, stored.controlUrl))).toEqual(new Set(['Bearer tok']))
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}')).toEqual(stored)
  })

  it('connects to the link target only after the user confirms it with their own token', async () => {
    window.history.replaceState(null, '', '/?control=http://192.168.1.77:8080')
    const fetchMock = vi.fn(async (url: string) =>
      url.includes('/api/events') ? jsonResponse([]) : jsonResponse(makeStatus({ deviceName: 'Second Phone' }))
    )
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)
    expect(fetchMock).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText('Access token'), { target: { value: 'new-token' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Second Phone')).toBeDefined()
    expect(new Set(authorizationFor(fetchMock, 'http://192.168.1.77:8080'))).toEqual(new Set(['Bearer new-token']))
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}')).toMatchObject({
      controlUrl: 'http://192.168.1.77:8080',
      token: 'new-token'
    })
  })

  it('does not interrupt the user when the URL repeats the saved values', async () => {
    window.history.replaceState(null, '', '/?control=http://192.168.1.50:8080/')
    vi.stubGlobal('fetch', phoneFetch())

    render(<App />)

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(await screen.findByText('SYSTEM ONLINE')).toBeDefined()
  })

  it('prefills the first-run dialog from the URL without a token', () => {
    localStorage.clear()
    window.history.replaceState(null, '', '/?control=http://192.168.1.50:8080&token=sneaky')
    vi.stubGlobal('fetch', vi.fn())

    render(<App />)

    expect((screen.getByLabelText('Phone control URL') as HTMLInputElement).value).toBe('http://192.168.1.50:8080')
    expect((screen.getByLabelText('Access token') as HTMLInputElement).value).toBe('')
  })
})

describe('App saving settings', () => {
  it('warns visibly when the browser refuses to store the settings, but still connects', async () => {
    vi.stubGlobal('fetch', phoneFetch())
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    render(<App />)

    fireEvent.change(screen.getByLabelText('Phone control URL'), { target: { value: stored.controlUrl } })
    fireEvent.change(screen.getByLabelText('Access token'), { target: { value: stored.token } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect((await screen.findByRole('alert')).textContent).toMatch(/could not be saved in this browser/i)
    expect(await screen.findByText('SYSTEM ONLINE')).toBeDefined()
  })

  it('shows no warning when saving works', async () => {
    vi.stubGlobal('fetch', phoneFetch())
    render(<App />)

    fireEvent.change(screen.getByLabelText('Phone control URL'), { target: { value: stored.controlUrl } })
    fireEvent.change(screen.getByLabelText('Access token'), { target: { value: stored.token } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await screen.findByText('SYSTEM ONLINE')
    expect(screen.queryByText(/could not be saved/i)).toBeNull()
  })
})

describe('App connection problems', () => {
  beforeEach(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored))
  })

  it('explains a 429 lockout instead of showing a generic offline state', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ error: 'locked' }, 429)))

    render(<App />)

    expect(await screen.findByText('TOO MANY ATTEMPTS')).toBeDefined()
    expect((await screen.findByRole('status')).textContent).toMatch(/wait a minute/i)
  })

  it('renders a status with missing sections instead of crashing', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        url.includes('/api/events')
          ? jsonResponse([])
          : jsonResponse({ deviceName: 'Sparse Phone', battery: { temperatureC: null } })
      )
    )

    render(<App />)

    expect(await screen.findByText('Sparse Phone')).toBeDefined()
    expect(screen.getByText('SYSTEM ONLINE')).toBeDefined()
    expect(screen.getAllByText('--').length).toBeGreaterThan(0)
  })
})
