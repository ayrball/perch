import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { App } from '../App'
import { STORAGE_KEY } from '../lib/config'
import { installFakeRtc } from '../test-utils/fakeRtc'
import { phoneFetch, stored } from '../test-utils/phone'

beforeEach(() => {
  installFakeRtc()
})

describe('Settings dialog accessibility', () => {
  beforeEach(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored))
    vi.stubGlobal('fetch', phoneFetch())
  })

  async function openSettings() {
    const view = render(<App />)
    await screen.findByText('SYSTEM ONLINE')
    const gear = screen.getByRole('button', { name: 'Settings' })
    gear.focus()
    fireEvent.click(gear)
    return { ...view, gear }
  }

  it('makes the page behind the dialog inert and moves focus into it', async () => {
    const { container } = await openSettings()

    const inert = container.querySelector('[inert]')
    expect(inert).not.toBeNull()
    expect(inert?.contains(screen.getByRole('dialog'))).toBe(false)
    expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true)
  })

  it('wraps Tab from the last control to the first, and Shift+Tab back', async () => {
    await openSettings()
    const close = screen.getByRole('button', { name: /close settings/i })
    const save = screen.getByRole('button', { name: 'Save' })

    save.focus()
    fireEvent.keyDown(save, { key: 'Tab' })
    expect(document.activeElement).toBe(close)

    fireEvent.keyDown(close, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(save)
  })

  it('does not interfere with Tab in the middle of the dialog', async () => {
    await openSettings()
    const field = screen.getByLabelText('Access token')

    field.focus()
    const notPrevented = fireEvent.keyDown(field, { key: 'Tab' })

    expect(notPrevented).toBe(true)
  })

  it('closes on Escape, restores the page and returns focus to the settings button', async () => {
    const { container, gear } = await openSettings()

    fireEvent.keyDown(window, { key: 'Escape' })

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(container.querySelector('[inert]')).toBeNull()
    expect(document.activeElement).toBe(gear)
  })

  it('returns focus to the settings button when closed with the close button', async () => {
    const { gear } = await openSettings()

    fireEvent.click(screen.getByRole('button', { name: /close settings/i }))

    expect(document.activeElement).toBe(gear)
  })
})

describe('Settings dialog before any configuration exists', () => {
  it('ignores Escape', () => {
    vi.stubGlobal('fetch', vi.fn())
    render(<App />)

    fireEvent.keyDown(window, { key: 'Escape' })

    expect(screen.getByRole('dialog')).toBeDefined()
  })

  it('traps Tab', () => {
    vi.stubGlobal('fetch', vi.fn())
    render(<App />)
    const first = screen.getByLabelText('Phone control URL')
    const save = screen.getByRole('button', { name: 'Save' })

    save.focus()
    fireEvent.keyDown(save, { key: 'Tab' })

    expect(document.activeElement).toBe(first)
  })
})
