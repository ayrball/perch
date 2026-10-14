export interface Rect {
  left: number
  top: number
  width: number
  height: number
}

/** The rectangle an `object-fit: contain` video occupies inside its container. */
export function containRect(containerW: number, containerH: number, videoW: number, videoH: number): Rect {
  if (containerW <= 0 || containerH <= 0 || videoW <= 0 || videoH <= 0) {
    return { left: 0, top: 0, width: containerW, height: containerH }
  }
  const scale = Math.min(containerW / videoW, containerH / videoH)
  const width = videoW * scale
  const height = videoH * scale
  return { left: (containerW - width) / 2, top: (containerH - height) / 2, width, height }
}
