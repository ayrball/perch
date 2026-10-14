import { describe, it, expect, vi } from 'vitest'
import { saveSnapshot } from '../lib/snapshot'

function fakeVideo(width: number, height: number): HTMLVideoElement {
  return { videoWidth: width, videoHeight: height } as HTMLVideoElement
}

describe('saveSnapshot', () => {
  it('refuses when the video has no frame yet', async () => {
    await expect(saveSnapshot(fakeVideo(0, 0))).rejects.toThrow('No video frame')
  })

  it('draws the frame to a canvas and downloads a local PNG with a timestamped name', async () => {
    const drawImage = vi.fn()
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      drawImage
    } as unknown as CanvasRenderingContext2D)
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(callback =>
      callback(new Blob(['png'], { type: 'image/png' }))
    )
    URL.createObjectURL = vi.fn(() => 'blob:perch-snapshot')
    URL.revokeObjectURL = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      expect(this.download).toBe('perch-20260105-070809.png')
      expect(this.href).toBe('blob:perch-snapshot')
      expect(document.body.contains(this)).toBe(true)
    })
    vi.useFakeTimers()

    const name = await saveSnapshot(fakeVideo(1280, 720), new Date(2026, 0, 5, 7, 8, 9))

    expect(name).toBe('perch-20260105-070809.png')
    expect(drawImage).toHaveBeenCalledWith(expect.anything(), 0, 0, 1280, 720)
    expect(click).toHaveBeenCalledTimes(1)
    expect(document.querySelector('a[download]')).toBeNull()
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1000)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:perch-snapshot')
  })
})
