export type CameraFacing = 'back' | 'front'
export type VideoQuality = '720p' | '1080p' | '4K'
export type BackupState = 'disconnected' | 'connected' | 'needs_auth'
export type EventType = 'MOTION' | 'FACE_STRANGER' | 'FACE_RECOGNIZED' | 'TAMPER' | 'PET'

export const VIDEO_QUALITIES: readonly VideoQuality[] = ['720p', '1080p', '4K']

export interface FaceBox {
  x: number
  y: number
  w: number
  h: number
}

export interface BatteryInfo {
  percent: number | null
  charging: boolean
  temperatureC: number | null
}

export interface StorageInfo {
  usedBytes: number
  limitBytes: number
  freeBytes: number
}

export interface BackupInfo {
  state: BackupState
  account: string | null
  autoUpload: boolean
  intervalMinutes: number
  pending: number
  uploaded: number
  quotaUsedBytes: number | null
  quotaTotalBytes: number | null
}

export interface Status {
  deviceName: string
  timestamp: number
  recording: boolean
  recordingSeconds: number
  segmentMinutes: number
  streaming: boolean
  /** Reported by newer phone builds; tolerated and passed through, not displayed. */
  streamState?: string
  motion: boolean
  faceCount: number
  faces: FaceBox[]
  flashlight: boolean
  sirenActive: boolean
  camera: CameraFacing
  videoQuality: VideoQuality
  throttled: boolean
  battery: BatteryInfo | null
  storage: StorageInfo | null
  backup: BackupInfo | null
}

export interface PerchEvent {
  id: number
  type: EventType
  title: string
  description: string
  timestamp: number
  faceCount: number
  confidence: number
}

export interface ClientConfig {
  controlUrl: string
  token: string
}

export const REQUEST_TIMEOUT_MS = 5000

export class PerchApiError extends Error {}

export class UnauthorizedError extends PerchApiError {
  constructor() {
    super('The phone rejected the access token.')
  }
}

export class NetworkError extends PerchApiError {
  constructor(message = 'Cannot reach the phone. Check the URL and that you are on the same network.') {
    super(message)
  }
}

export class HttpError extends PerchApiError {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/** The phone locks the client out after too many failed sign-ins in a minute. */
export class RateLimitedError extends HttpError {
  constructor() {
    super(429, 'Too many attempts. Wait a minute and try again.')
  }
}

type JsonRecord = Record<string, unknown>

const isRecord = (value: unknown): value is JsonRecord =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

const numberOr = (value: unknown, fallback: number): number =>
  typeof value === 'number' && Number.isFinite(value) ? value : fallback

const numberOrNull = (value: unknown): number | null =>
  typeof value === 'number' && Number.isFinite(value) ? value : null

const stringOr = (value: unknown, fallback: string): string => (typeof value === 'string' ? value : fallback)

function normalizeFaces(value: unknown): FaceBox[] {
  if (!Array.isArray(value)) return []
  const faces: FaceBox[] = []
  for (const item of value) {
    if (!isRecord(item)) continue
    const { x, y, w, h } = item
    if ([x, y, w, h].every(n => typeof n === 'number' && Number.isFinite(n))) {
      faces.push({ x: x as number, y: y as number, w: w as number, h: h as number })
    }
  }
  return faces
}

function normalizeBattery(value: unknown): BatteryInfo | null {
  if (!isRecord(value)) return null
  return {
    percent: numberOrNull(value.percent),
    charging: value.charging === true,
    temperatureC: numberOrNull(value.temperatureC),
  }
}

function normalizeStorage(value: unknown): StorageInfo | null {
  if (!isRecord(value)) return null
  return {
    usedBytes: numberOr(value.usedBytes, 0),
    limitBytes: numberOr(value.limitBytes, 0),
    freeBytes: numberOr(value.freeBytes, 0),
  }
}

function normalizeBackup(value: unknown): BackupInfo | null {
  if (!isRecord(value)) return null
  const state: BackupState =
    value.state === 'connected' || value.state === 'needs_auth' ? value.state : 'disconnected'
  return {
    state,
    account: typeof value.account === 'string' && value.account ? value.account : null,
    autoUpload: value.autoUpload === true,
    intervalMinutes: numberOr(value.intervalMinutes, 0),
    pending: numberOr(value.pending, 0),
    uploaded: numberOr(value.uploaded, 0),
    quotaUsedBytes: numberOrNull(value.quotaUsedBytes),
    quotaTotalBytes: numberOrNull(value.quotaTotalBytes),
  }
}

/** Validates a status payload, filling safe defaults so one bad field cannot break the page. */
export function normalizeStatus(raw: unknown): Status {
  if (!isRecord(raw)) throw new TypeError('Status is not an object.')
  const faces = normalizeFaces(raw.faces)
  const status: Status = {
    deviceName: stringOr(raw.deviceName, 'Phone'),
    timestamp: numberOr(raw.timestamp, 0),
    recording: raw.recording === true,
    recordingSeconds: numberOr(raw.recordingSeconds, 0),
    segmentMinutes: numberOr(raw.segmentMinutes, 0),
    streaming: raw.streaming === true,
    motion: raw.motion === true,
    faceCount: numberOr(raw.faceCount, faces.length),
    faces,
    flashlight: raw.flashlight === true,
    sirenActive: raw.sirenActive === true,
    camera: raw.camera === 'front' ? 'front' : 'back',
    videoQuality: VIDEO_QUALITIES.find(quality => quality === raw.videoQuality) ?? '1080p',
    throttled: raw.throttled === true,
    battery: normalizeBattery(raw.battery),
    storage: normalizeStorage(raw.storage),
    backup: normalizeBackup(raw.backup),
  }
  if (typeof raw.streamState === 'string') status.streamState = raw.streamState
  return status
}

/** Validates the events payload, dropping entries that cannot be shown. */
export function normalizeEvents(raw: unknown): PerchEvent[] {
  if (!Array.isArray(raw)) throw new TypeError('Events is not an array.')
  const events: PerchEvent[] = []
  for (const item of raw) {
    if (!isRecord(item)) continue
    const id = numberOrNull(item.id)
    const timestamp = numberOrNull(item.timestamp)
    if (id === null || timestamp === null || typeof item.type !== 'string') continue
    events.push({
      id,
      type: item.type as EventType,
      title: stringOr(item.title, ''),
      description: stringOr(item.description, ''),
      timestamp,
      faceCount: numberOr(item.faceCount, 0),
      confidence: numberOr(item.confidence, 0),
    })
  }
  return events
}

async function errorMessage(response: Response): Promise<string> {
  try {
    const body: unknown = await response.json()
    if (isRecord(body) && typeof body.error === 'string' && body.error) return body.error
  } catch {
    // Fall through to the generic message.
  }
  return `Phone returned HTTP ${response.status}.`
}

export class PerchClient {
  private readonly baseUrl: string
  private readonly token: string

  constructor(config: ClientConfig) {
    this.baseUrl = config.controlUrl.replace(/\/+$/, '')
    this.token = config.token
  }

  getStatus(): Promise<Status> {
    return this.request('GET', '/api/status', normalizeStatus)
  }

  getEvents(limit = 50): Promise<PerchEvent[]> {
    return this.request('GET', `/api/events?limit=${encodeURIComponent(String(limit))}`, normalizeEvents)
  }

  /** Sets the flashlight, or toggles it when `on` is omitted. */
  setFlashlight(on?: boolean): Promise<Status> {
    return this.request('POST', '/api/flashlight', normalizeStatus, on === undefined ? undefined : { on })
  }

  startSiren(seconds: number): Promise<Status> {
    return this.request('POST', '/api/siren', normalizeStatus, { seconds })
  }

  stopSiren(): Promise<Status> {
    return this.request('POST', '/api/siren/stop', normalizeStatus)
  }

  flipCamera(): Promise<Status> {
    return this.request('POST', '/api/camera/flip', normalizeStatus)
  }

  setQuality(quality: VideoQuality): Promise<Status> {
    return this.request('POST', '/api/quality', normalizeStatus, { quality })
  }

  /** The timeout covers the whole exchange, including reading the response body. */
  private async request<T>(
    method: 'GET' | 'POST',
    path: string,
    parse: (raw: unknown) => T,
    body?: unknown,
  ): Promise<T> {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
    const timedOut = () => new NetworkError('The phone did not respond in time.')
    const headers: Record<string, string> = { Authorization: `Bearer ${this.token}` }
    if (body !== undefined) headers['Content-Type'] = 'application/json'

    try {
      let response: Response
      try {
        response = await fetch(`${this.baseUrl}${path}`, {
          method,
          headers,
          body: body === undefined ? undefined : JSON.stringify(body),
          signal: controller.signal,
        })
      } catch {
        throw controller.signal.aborted ? timedOut() : new NetworkError()
      }

      if (response.status === 401) throw new UnauthorizedError()
      if (response.status === 429) throw new RateLimitedError()
      if (!response.ok) throw new HttpError(response.status, await errorMessage(response))

      try {
        return parse(await response.json())
      } catch {
        throw controller.signal.aborted
          ? timedOut()
          : new HttpError(response.status, 'The phone sent an unreadable response.')
      }
    } finally {
      clearTimeout(timer)
    }
  }
}

export function describeError(error: unknown): string {
  return error instanceof Error ? error.message : 'Unexpected error.'
}
