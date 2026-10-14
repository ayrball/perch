const pad = (value: number) => String(value).padStart(2, '0')

/** Shown instead of NaN, Infinity or "Invalid Date" when a value is missing or garbage. */
export const PLACEHOLDER = '\u2014'

/** "Just now", "4 min ago", "3 hr ago", "2 d ago", then a calendar date. */
export function formatRelativeTime(timestamp: number, now: number = Date.now()): string {
  if (!Number.isFinite(timestamp) || !Number.isFinite(now)) return PLACEHOLDER
  const seconds = Math.floor((now - timestamp) / 1000)
  if (seconds < 60) return 'Just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} hr ago`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} d ago`
  return new Date(timestamp).toLocaleDateString()
}

/** mm:ss, or h:mm:ss from one hour on. */
export function formatClock(totalSeconds: number): string {
  if (!Number.isFinite(totalSeconds)) return PLACEHOLDER
  const safe = Math.max(0, Math.floor(totalSeconds))
  const hours = Math.floor(safe / 3600)
  const minutes = Math.floor((safe % 3600) / 60)
  const seconds = safe % 60
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`
}

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes)) return PLACEHOLDER
  if (bytes < 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  // Compare the rounded value so 1023.6 B becomes "1.0 KB" instead of "1024 B".
  while (unit < units.length - 1 && Math.round(value) >= 1024) {
    value /= 1024
    unit++
  }
  const digits = unit === 0 || value >= 99.95 ? 0 : 1
  return `${value.toFixed(digits)} ${units[unit]}`
}

export function formatBitrate(bitsPerSecond: number): string {
  if (!Number.isFinite(bitsPerSecond) || bitsPerSecond < 0) return PLACEHOLDER
  if (Math.round(bitsPerSecond / 1000) >= 1000) return `${(bitsPerSecond / 1_000_000).toFixed(1)} Mb/s`
  return `${Math.round(bitsPerSecond / 1000)} kb/s`
}

/** perch-YYYYMMDD-HHMMSS.png in local time. */
export function snapshotFilename(date: Date): string {
  const day = `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}`
  const time = `${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
  return `perch-${day}-${time}.png`
}
