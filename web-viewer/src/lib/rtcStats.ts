export interface StreamStats {
  bitrateBps: number | null
  width: number | null
  height: number | null
  fps: number | null
}

export interface InboundVideoSample {
  bytesReceived: number
  timestamp: number
  width: number | null
  height: number | null
  fps: number | null
}

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/** Extracts the inbound video RTP sample from a getStats() report. */
export function readInboundVideo(report: RTCStatsReport): InboundVideoSample | null {
  let sample: InboundVideoSample | null = null
  report.forEach((entry: Record<string, unknown>) => {
    if (entry.type !== 'inbound-rtp') return
    if ((entry.kind ?? entry.mediaType) !== 'video') return
    const bytes = numberOrNull(entry.bytesReceived)
    const timestamp = numberOrNull(entry.timestamp)
    if (bytes === null || timestamp === null) return
    sample = {
      bytesReceived: bytes,
      timestamp,
      width: numberOrNull(entry.frameWidth),
      height: numberOrNull(entry.frameHeight),
      fps: numberOrNull(entry.framesPerSecond),
    }
  })
  return sample
}

/** Bitrate is derived from the byte delta since `previous`; null until two samples exist. */
export function toStreamStats(current: InboundVideoSample, previous: InboundVideoSample | null): StreamStats {
  let bitrateBps: number | null = null
  if (previous && current.timestamp > previous.timestamp && current.bytesReceived >= previous.bytesReceived) {
    const seconds = (current.timestamp - previous.timestamp) / 1000
    bitrateBps = ((current.bytesReceived - previous.bytesReceived) * 8) / seconds
  }
  return { bitrateBps, width: current.width, height: current.height, fps: current.fps }
}
