import type { BackupInfo, PerchEvent, Status } from '../lib/api'

export function makeBackup(overrides: Partial<BackupInfo> = {}): BackupInfo {
  return {
    state: 'connected',
    account: 'owner@example.com',
    autoUpload: true,
    intervalMinutes: 15,
    pending: 2,
    uploaded: 40,
    quotaUsedBytes: 4 * 1024 ** 3,
    quotaTotalBytes: 16 * 1024 ** 3,
    ...overrides
  }
}

export function makeStatus(overrides: Partial<Status> = {}): Status {
  return {
    deviceName: 'Test Phone',
    timestamp: 1_730_000_000_000,
    recording: true,
    recordingSeconds: 252,
    segmentMinutes: 5,
    streaming: true,
    streamState: 'live',
    motion: false,
    faceCount: 1,
    faces: [{ x: 0.38, y: 0.25, w: 0.22, h: 0.32 }],
    flashlight: false,
    sirenActive: false,
    camera: 'back',
    videoQuality: '1080p',
    throttled: false,
    battery: { percent: 87, charging: true, temperatureC: 31.27 },
    storage: { usedBytes: 2 * 1024 ** 3, limitBytes: 8 * 1024 ** 3, freeBytes: 20 * 1024 ** 3 },
    backup: makeBackup(),
    ...overrides
  }
}

export function makeEvent(overrides: Partial<PerchEvent> = {}): PerchEvent {
  return {
    id: 1,
    type: 'MOTION',
    title: 'Motion Detected',
    description: 'Movement in the hallway',
    timestamp: Date.now(),
    faceCount: 0,
    confidence: 0.8,
    ...overrides
  }
}

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers }
  })
}
