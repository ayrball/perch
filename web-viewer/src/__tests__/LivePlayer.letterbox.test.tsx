import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { LivePlayer } from '../components/LivePlayer'
import { makeStatus } from '../test-utils/fixtures'
import { FakePeerConnection, installFakeRtc, whepFetchMock } from '../test-utils/fakeRtc'
import { FakeResizeObserver } from '../test-utils/fakeResizeObserver'

const WHEP_URL = 'http://192.168.1.100:8889/perch/whep'

beforeEach(() => {
  installFakeRtc()
  vi.stubGlobal('fetch', whepFetchMock())
})

async function connect() {
  await act(async () => {
    await Promise.resolve()
  })
  await act(async () => {
    FakePeerConnection.instances[0].simulateTrack()
    FakePeerConnection.instances[0].simulateState('connected')
  })
}

describe('LivePlayer face overlay alignment', () => {
  it('confines face boxes to the letterboxed picture of a portrait video in a 16:9 player', async () => {
    const status = makeStatus({ faces: [{ x: 0.5, y: 0.25, w: 0.2, h: 0.1 }] })
    render(<LivePlayer whepUrl={WHEP_URL} status={status} connection="online" />)
    await connect()

    // A 9:16 phone stream inside a 1600x900 player leaves bars left and right.
    const video = screen.getByLabelText('Live camera stream')
    Object.defineProperty(video, 'videoWidth', { configurable: true, value: 1080 })
    Object.defineProperty(video, 'videoHeight', { configurable: true, value: 1920 })
    act(() => {
      fireEvent.loadedMetadata(video)
      FakeResizeObserver.resizeAll(1600, 900)
    })

    const layer = screen.getByTestId('face-box').parentElement as HTMLElement
    expect(layer.style.height).toBe('900px')
    expect(parseFloat(layer.style.width)).toBeCloseTo(506.25)
    expect(parseFloat(layer.style.left)).toBeCloseTo((1600 - 506.25) / 2)
    expect(parseFloat(layer.style.top)).toBe(0)
    // Percentages are relative to that picture, not to the whole player.
    const box = screen.getByTestId('face-box')
    expect(box.style.left).toBe('50%')
    expect(box.style.top).toBe('25%')
  })

  it('uses the whole player until the video size is known', async () => {
    render(<LivePlayer whepUrl={WHEP_URL} status={makeStatus()} connection="online" />)
    await connect()

    const layer = screen.getByTestId('face-box').parentElement as HTMLElement
    expect(layer.style.inset).toBe('0px')
    expect(layer.style.width).toBe('')
  })

  it('follows the player when it is resized', async () => {
    render(<LivePlayer whepUrl={WHEP_URL} status={makeStatus()} connection="online" />)
    await connect()
    const video = screen.getByLabelText('Live camera stream')
    Object.defineProperty(video, 'videoWidth', { configurable: true, value: 1920 })
    Object.defineProperty(video, 'videoHeight', { configurable: true, value: 1080 })
    act(() => {
      fireEvent.loadedMetadata(video)
      FakeResizeObserver.resizeAll(800, 800)
    })

    const layer = screen.getByTestId('face-box').parentElement as HTMLElement
    expect(parseFloat(layer.style.width)).toBe(800)
    expect(parseFloat(layer.style.height)).toBeCloseTo(450)
    expect(parseFloat(layer.style.top)).toBeCloseTo(175)
  })
})
