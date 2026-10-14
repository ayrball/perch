import { snapshotFilename } from './format'

const REVOKE_DELAY_MS = 1000

/** Draws the current video frame to a canvas and downloads it as a PNG. */
export async function saveSnapshot(video: HTMLVideoElement, now: Date = new Date()): Promise<string> {
  if (!video.videoWidth || !video.videoHeight) throw new Error('No video frame to capture yet.')

  const canvas = document.createElement('canvas')
  canvas.width = video.videoWidth
  canvas.height = video.videoHeight
  const context = canvas.getContext('2d')
  if (!context) throw new Error('Canvas is not available in this browser.')
  context.drawImage(video, 0, 0, canvas.width, canvas.height)

  const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/png'))
  if (!blob) throw new Error('Could not encode the snapshot.')

  const filename = snapshotFilename(now)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  // Some browsers ignore clicks on detached anchors, and revoking too early can cancel the download.
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), REVOKE_DELAY_MS)
  return filename
}
