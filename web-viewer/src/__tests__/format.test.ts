import { describe, it, expect } from 'vitest'
import {
  PLACEHOLDER,
  formatBitrate,
  formatBytes,
  formatClock,
  formatRelativeTime,
  snapshotFilename
} from '../lib/format'

describe('formatRelativeTime', () => {
  const now = 1_730_000_000_000

  it('says "Just now" under a minute and for clock skew into the future', () => {
    expect(formatRelativeTime(now - 5_000, now)).toBe('Just now')
    expect(formatRelativeTime(now - 59_000, now)).toBe('Just now')
    expect(formatRelativeTime(now + 30_000, now)).toBe('Just now')
  })

  it('formats minutes, hours and days', () => {
    expect(formatRelativeTime(now - 60_000, now)).toBe('1 min ago')
    expect(formatRelativeTime(now - 4 * 60_000, now)).toBe('4 min ago')
    expect(formatRelativeTime(now - 59 * 60_000, now)).toBe('59 min ago')
    expect(formatRelativeTime(now - 60 * 60_000, now)).toBe('1 hr ago')
    expect(formatRelativeTime(now - 5 * 3_600_000, now)).toBe('5 hr ago')
    expect(formatRelativeTime(now - 26 * 3_600_000, now)).toBe('1 d ago')
  })

  it('uses a placeholder instead of "Invalid Date" for garbage timestamps', () => {
    expect(formatRelativeTime(Number.NaN, now)).toBe(PLACEHOLDER)
    expect(formatRelativeTime(Infinity, now)).toBe(PLACEHOLDER)
    expect(formatRelativeTime(now, Number.NaN)).toBe(PLACEHOLDER)
  })

  it('falls back to a date after a week', () => {
    const old = now - 10 * 86_400_000
    expect(formatRelativeTime(old, now)).toBe(new Date(old).toLocaleDateString())
  })
})

describe('formatClock', () => {
  it('formats mm:ss and h:mm:ss', () => {
    expect(formatClock(0)).toBe('00:00')
    expect(formatClock(252)).toBe('04:12')
    expect(formatClock(3_599)).toBe('59:59')
    expect(formatClock(3_661)).toBe('1:01:01')
  })

  it('clamps negatives and uses a placeholder for non-finite input', () => {
    expect(formatClock(-5)).toBe('00:00')
    expect(formatClock(Number.NaN)).toBe(PLACEHOLDER)
    expect(formatClock(Infinity)).toBe(PLACEHOLDER)
  })
})

describe('formatBytes', () => {
  it('scales units', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(8 * 1024 ** 3)).toBe('8.0 GB')
    expect(formatBytes(150 * 1024 ** 2)).toBe('150 MB')
  })

  it('tolerates invalid input', () => {
    expect(formatBytes(Number.NaN)).toBe(PLACEHOLDER)
    expect(formatBytes(Infinity)).toBe(PLACEHOLDER)
    expect(formatBytes(-1)).toBe('0 B')
  })

  it('rounds before choosing the unit so no value shows as 1024', () => {
    expect(formatBytes(1023)).toBe('1023 B')
    expect(formatBytes(1023.6)).toBe('1.0 KB')
    expect(formatBytes(1024 * 1024 - 1)).toBe('1.0 MB')
    expect(formatBytes(1024 ** 3 - 1)).toBe('1.0 GB')
  })

  it('does not print 100.0 for values just under 100', () => {
    expect(formatBytes(99.96 * 1024)).toBe('100 KB')
    expect(formatBytes(99.94 * 1024)).toBe('99.9 KB')
  })
})

describe('formatBitrate', () => {
  it('switches between kb/s and Mb/s', () => {
    expect(formatBitrate(850_000)).toBe('850 kb/s')
    expect(formatBitrate(2_400_000)).toBe('2.4 Mb/s')
  })

  it('rounds up into Mb/s instead of printing 1000 kb/s', () => {
    expect(formatBitrate(999_400)).toBe('999 kb/s')
    expect(formatBitrate(999_600)).toBe('1.0 Mb/s')
  })

  it('uses a placeholder for non-finite or negative input', () => {
    expect(formatBitrate(Number.NaN)).toBe(PLACEHOLDER)
    expect(formatBitrate(Infinity)).toBe(PLACEHOLDER)
    expect(formatBitrate(-5)).toBe(PLACEHOLDER)
  })
})

describe('snapshotFilename', () => {
  it('uses perch-YYYYMMDD-HHMMSS.png in local time', () => {
    expect(snapshotFilename(new Date(2026, 0, 5, 7, 8, 9))).toBe('perch-20260105-070809.png')
  })
})
