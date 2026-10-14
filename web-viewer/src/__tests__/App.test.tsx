import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { App } from '../App'
import { STORAGE_KEY } from '../lib/config'
import { jsonResponse, makeStatus } from '../test-utils/fixtures'
import { installFakeRtc } from '../test-utils/fakeRtc'
import { phoneFetch, stored } from '../test-utils/phone'

beforeEach(() => {
  installFakeRtc()
})

describe('App unconfigured', () => {
  it('opens the settings dialog and shows NOT CONFIGURED with no fake data', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)

    expect(screen.getByRole('dialog', { name: /connection settings/i })).toBeDefined()
    expect(screen.getAllByText('NOT CONFIGURED').length).toBeGreaterThan(0)
    expect(screen.getByText('No events yet')).toBeDefined()
    expect(document.body.textContent).not.toMatch(/92%|33\.4|Pixel 6a|SYNCED|4\.2 GB|LIVING ROOM|Simulat/)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('cannot be dismissed until configured, then validates and saves', async () => {
    vi.stubGlobal('fetch', phoneFetch())
    render(<App />)
    expect(screen.queryByRole('button', { name: /close settings/i })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(screen.getAllByRole('alert').length).toBe(2)
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()

    fireEvent.change(screen.getByLabelText('Phone control URL'), { target: { value: stored.controlUrl } })
    fireEvent.change(screen.getByLabelText('Access token'), { target: { value: stored.token } })
    fireEvent.change(screen.getByLabelText('Live stream (WHEP) URL'), { target: { value: stored.whepUrl } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}')).toEqual(stored)
    expect(await screen.findByText('SYSTEM ONLINE')).toBeDefined()
  })

  it('tests the connection and shows the device name', async () => {
    vi.stubGlobal('fetch', phoneFetch(makeStatus({ deviceName: 'Hallway Cam' })))
    render(<App />)

    fireEvent.change(screen.getByLabelText('Phone control URL'), { target: { value: stored.controlUrl } })
    fireEvent.change(screen.getByLabelText('Access token'), { target: { value: stored.token } })
    fireEvent.click(screen.getByRole('button', { name: 'Test connection' }))

    expect((await screen.findByRole('status')).textContent).toBe('Connected to Hallway Cam.')
  })

  it('reports a rejected token in the connection test', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ error: 'unauthorized' }, 401)))
    render(<App />)

    fireEvent.change(screen.getByLabelText('Phone control URL'), { target: { value: stored.controlUrl } })
    fireEvent.change(screen.getByLabelText('Access token'), { target: { value: 'wrong' } })
    fireEvent.click(screen.getByRole('button', { name: 'Test connection' }))

    expect((await screen.findByRole('alert')).textContent).toMatch(/rejected the access token/i)
  })
})

describe('App configured', () => {
  beforeEach(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored))
  })

  it('shows real device name, battery, temperature and derived cards', async () => {
    vi.stubGlobal('fetch', phoneFetch(makeStatus({ segmentMinutes: 10, videoQuality: '720p', camera: 'front' })))

    render(<App />)

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(await screen.findByText('SYSTEM ONLINE')).toBeDefined()
    expect(screen.getByText('Test Phone')).toBeDefined()
    expect(screen.getByText('87%')).toBeDefined()
    expect(screen.getByText('31.3°C')).toBeDefined()
    expect(screen.getByLabelText('Charging')).toBeDefined()
    expect(screen.getByText('Continuous (10m chunks)')).toBeDefined()
    expect(screen.getByText('720p • Front camera')).toBeDefined()
    expect(screen.getByText('No motion • 1 face')).toBeDefined()
    expect(screen.getByText('Google Drive (Active)')).toBeDefined()
    expect(await screen.findByText('Hall motion')).toBeDefined()
  })

  it('shows SIGN-IN FAILED on a 401', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ error: 'unauthorized' }, 401)))

    render(<App />)

    expect(await screen.findByText('SIGN-IN FAILED')).toBeDefined()
  })

  it('shows OFFLINE when the phone is unreachable and allows reopening settings', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))

    render(<App />)

    expect(await screen.findByText('OFFLINE')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    expect(screen.getByRole('dialog')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: /close settings/i }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
