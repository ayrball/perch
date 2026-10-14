import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { LivePlayer } from '../components/LivePlayer'
import { saveSnapshot } from '../lib/snapshot'
import { makeStatus } from '../test-utils/fixtures'
import { FakePeerConnection, installFakeRtc, whepFetchMock } from '../test-utils/fakeRtc'

vi.mock('../lib/snapshot', () => ({ saveSnapshot: vi.fn() }))

const WHEP_URL = 'http://192.168.1.100:8889/perch/whep'

async function connectStream() {
  await act(async () => {
    await Promise.resolve()
  })
  const pc = FakePeerConnection.instances[0]
  await act(async () => {
    pc.simulateTrack()
    pc.simulateState('connected')
  })
  return pc
}

beforeEach(() => {
  installFakeRtc()
  vi.stubGlobal('fetch', whepFetchMock())
  vi.mocked(saveSnapshot).mockReset()
})

describe('LivePlayer without a stream URL', () => {
  it('shows a not-configured state instead of fake content', () => {
    render(<LivePlayer whepUrl="" status={null} connection="unconfigured" />)

    expect(screen.getByText('NOT CONFIGURED')).toBeDefined()
    expect(screen.getByText('No live stream configured')).toBeDefined()
    expect(screen.queryByText(/REC/)).toBeNull()
    expect(FakePeerConnection.instances).toHaveLength(0)
  })

  it('disables snapshot and play controls while there is no video', () => {
    render(<LivePlayer whepUrl="" status={null} connection="unconfigured" />)

    expect((screen.getByRole('button', { name: /snapshot/i }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: 'Play' }) as HTMLButtonElement).disabled).toBe(true)
  })
})

describe('LivePlayer with a WHEP stream', () => {
  it('goes from CONNECTING to LIVE and attaches the stream to the video element', async () => {
    render(<LivePlayer whepUrl={WHEP_URL} status={null} connection="connecting" />)
    expect(screen.getByText('CONNECTING')).toBeDefined()

    await connectStream()

    expect(screen.getByText('LIVE')).toBeDefined()
    const video = screen.getByLabelText('Live camera stream') as HTMLVideoElement
    expect(video.srcObject).not.toBeNull()
    expect(video.autoplay).toBe(true)
    expect(video.muted).toBe(true)
  })

  it('shows REC timer, motion, throttle badges and face boxes from the real status', async () => {
    const status = makeStatus({
      recording: true,
      recordingSeconds: 252,
      motion: true,
      throttled: true,
      faces: [{ x: 0.38, y: 0.25, w: 0.22, h: 0.32 }]
    })
    render(<LivePlayer whepUrl={WHEP_URL} status={status} connection="online" />)
    await connectStream()

    expect(screen.getByText('REC 04:12')).toBeDefined()
    expect(screen.getByText('MOTION DETECTED')).toBeDefined()
    expect(screen.getByText('THROTTLED')).toBeDefined()
    const box = screen.getByTestId('face-box')
    expect(box.style.left).toBe('38%')
    expect(box.style.top).toBe('25%')
    expect(box.style.width).toBe('22%')
    expect(box.style.height).toBe('32%')
  })

  it('omits status overlays when the phone is not online', async () => {
    render(<LivePlayer whepUrl={WHEP_URL} status={makeStatus({ motion: true })} connection="offline" />)
    await connectStream()

    expect(screen.queryByText(/REC/)).toBeNull()
    expect(screen.queryByText('MOTION DETECTED')).toBeNull()
    expect(screen.queryByTestId('face-box')).toBeNull()
  })

  it('does not show REC when the phone is not recording', async () => {
    render(<LivePlayer whepUrl={WHEP_URL} status={makeStatus({ recording: false })} connection="online" />)
    await connectStream()

    expect(screen.queryByText(/REC/)).toBeNull()
  })

  it('toggles mute on the video element', async () => {
    render(<LivePlayer whepUrl={WHEP_URL} status={null} connection="connecting" />)
    await connectStream()
    const video = screen.getByLabelText('Live camera stream') as HTMLVideoElement

    fireEvent.click(screen.getByRole('button', { name: 'Unmute' }))
    expect(video.muted).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Mute' }))
    expect(video.muted).toBe(true)
  })

  it('saves a local snapshot and reports the file name', async () => {
    vi.mocked(saveSnapshot).mockResolvedValue('perch-20260105-070809.png')
    render(<LivePlayer whepUrl={WHEP_URL} status={null} connection="connecting" />)
    await connectStream()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /snapshot/i }))
    })

    expect(saveSnapshot).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status').textContent).toContain('perch-20260105-070809.png')
    expect(document.body.textContent).not.toMatch(/drive/i)
  })

  it('reports snapshot failures inline', async () => {
    vi.mocked(saveSnapshot).mockRejectedValue(new Error('No video frame to capture yet.'))
    render(<LivePlayer whepUrl={WHEP_URL} status={null} connection="connecting" />)
    await connectStream()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /snapshot/i }))
    })

    expect(screen.getByRole('alert').textContent).toBe('No video frame to capture yet.')
  })

  it('shows the reconnecting state when the stream request fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 404 })))
    render(<LivePlayer whepUrl={WHEP_URL} status={null} connection="connecting" />)

    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(await screen.findByText('RECONNECTING')).toBeDefined()
    expect(screen.getByText('Stream server returned HTTP 404.')).toBeDefined()
  })
})
