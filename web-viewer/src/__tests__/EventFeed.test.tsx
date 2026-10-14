import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { EventFeed } from '../components/EventFeed'
import type { PerchEvent } from '../lib/api'
import { makeEvent } from '../test-utils/fixtures'

const NOW = 1_730_000_000_000

const events: PerchEvent[] = [
  makeEvent({ id: 1, type: 'MOTION', title: 'Motion Detected', timestamp: NOW - 4 * 60_000, confidence: 0.88 }),
  makeEvent({ id: 2, type: 'FACE_STRANGER', title: 'Stranger at door', timestamp: NOW - 10_000, confidence: 0.94 }),
  makeEvent({ id: 3, type: 'FACE_RECOGNIZED', title: 'Alex recognized', timestamp: NOW - 3_600_000 }),
  makeEvent({ id: 4, type: 'PET', title: 'Pet activity', timestamp: NOW - 18 * 60_000, confidence: 0.79 }),
  makeEvent({ id: 5, type: 'TAMPER', title: 'Camera moved', timestamp: NOW - 2 * 3_600_000, confidence: 0 })
]

function renderFeed(list: PerchEvent[] = events) {
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
  return render(<EventFeed events={list} connection="online" />)
}

describe('EventFeed', () => {
  it('lists every event type under All, including PET and TAMPER', () => {
    renderFeed()

    for (const title of ['Motion Detected', 'Stranger at door', 'Alex recognized', 'Pet activity', 'Camera moved']) {
      expect(screen.getByText(title)).toBeDefined()
    }
  })

  it('filters People to face events only', () => {
    renderFeed()

    fireEvent.click(screen.getByRole('button', { name: 'People' }))

    expect(screen.getByText('Stranger at door')).toBeDefined()
    expect(screen.getByText('Alex recognized')).toBeDefined()
    expect(screen.queryByText('Motion Detected')).toBeNull()
    expect(screen.queryByText('Pet activity')).toBeNull()
    expect(screen.queryByText('Camera moved')).toBeNull()
  })

  it('filters Motion to motion events only, then returns to All', () => {
    renderFeed()

    fireEvent.click(screen.getByRole('button', { name: 'Motion' }))
    expect(screen.getByText('Motion Detected')).toBeDefined()
    expect(screen.queryByText('Stranger at door')).toBeNull()
    expect(screen.queryByText('Pet activity')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'All' }))
    expect(screen.getByText('Pet activity')).toBeDefined()
    expect(screen.getByText('Camera moved')).toBeDefined()
  })

  it('shows relative times', () => {
    renderFeed()

    expect(screen.getByText('Just now')).toBeDefined()
    expect(screen.getByText('4 min ago')).toBeDefined()
    expect(screen.getByText('18 min ago')).toBeDefined()
    expect(screen.getByText('1 hr ago')).toBeDefined()
  })

  it('shows confidence as a percentage only when the event has one', () => {
    renderFeed()

    expect(screen.getByText('Confidence: 88%')).toBeDefined()
    expect(screen.getByText('Confidence: 94%')).toBeDefined()
    expect(screen.getAllByText(/Confidence:/)).toHaveLength(4)
  })

  it('shows an empty state without events', () => {
    renderFeed([])

    expect(screen.getByText('No events yet')).toBeDefined()
  })

  it('explains an empty filter result', () => {
    renderFeed([makeEvent({ id: 1, type: 'MOTION' })])

    fireEvent.click(screen.getByRole('button', { name: 'People' }))

    expect(screen.getByText('No events match this filter')).toBeDefined()
  })
})
