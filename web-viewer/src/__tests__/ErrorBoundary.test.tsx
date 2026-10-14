import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ErrorBoundary } from '../components/ErrorBoundary'

function Bomb(): never {
  throw new Error('boom')
}

describe('ErrorBoundary', () => {
  it('renders its children normally', () => {
    render(
      <ErrorBoundary>
        <p>all good</p>
      </ErrorBoundary>
    )

    expect(screen.getByText('all good')).toBeDefined()
  })

  it('replaces a crashed tree with a recovery screen instead of a blank page', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)

    render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>
    )

    expect(screen.getByRole('alert').textContent).toMatch(/something went wrong/i)
    expect(screen.getByRole('button', { name: /reload viewer/i })).toBeDefined()
  })
})
