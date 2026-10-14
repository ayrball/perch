import React, { useEffect, useRef, useState } from 'react'
import {
  Flashlight,
  SwitchCamera,
  BellRing,
  Sliders,
  CheckCircle2,
  AlertTriangle,
  CloudOff,
  HardDrive
} from 'lucide-react'
import {
  VIDEO_QUALITIES,
  describeError,
  type BackupInfo,
  type PerchClient,
  type Status,
  type VideoQuality
} from '../lib/api'
import { formatBytes } from '../lib/format'
import type { Connection } from '../hooks/usePerchStatus'

export const SIREN_SECONDS = 10

interface RemoteControlsProps {
  client: PerchClient | null
  status: Status | null
  connection: Connection
  /** Called with the status the phone returns after a command. */
  onStatus: (status: Status) => void
}

type Action = 'flashlight' | 'siren' | 'flip' | 'quality'

/** Values shown immediately while a command is in flight; the server's answer replaces them. */
interface Optimistic {
  flashlight?: boolean
  sirenActive?: boolean
  videoQuality?: VideoQuality
}

const QUALITY_LABELS: Record<VideoQuality, string> = {
  '720p': '720p HD',
  '1080p': '1080p FHD',
  '4K': '4K UHD'
}

const DISABLED_REASONS: Record<Exclude<Connection, 'online'>, string> = {
  rate_limited: 'Too many attempts. Wait a minute and try again',
  unconfigured: 'Configure the phone connection in Settings first',
  connecting: 'Connecting to the phone...',
  unauthorized: 'The phone rejected the access token',
  offline: 'The phone is offline'
}

const BACKUP_BADGES: Record<BackupInfo['state'], { label: string; classes: string }> = {
  connected: { label: 'CONNECTED', classes: 'text-[#00E676] bg-[#00E676]/10 border-[#00E676]/30' },
  needs_auth: { label: 'NEEDS SIGN-IN', classes: 'text-[#FF9100] bg-[#FF9100]/10 border-[#FF9100]/30' },
  disconnected: { label: 'NOT CONNECTED', classes: 'text-[#8899AA] bg-[#8899AA]/10 border-[#8899AA]/30' }
}

const BACKUP_ICONS: Record<BackupInfo['state'], React.ReactNode> = {
  connected: <CheckCircle2 className="w-4 h-4" />,
  needs_auth: <AlertTriangle className="w-4 h-4" />,
  disconnected: <CloudOff className="w-4 h-4" />
}

const BACKUP_ICON_CLASSES: Record<BackupInfo['state'], string> = {
  connected: 'bg-[#00E676]/15 text-[#00E676]',
  needs_auth: 'bg-[#FF9100]/15 text-[#FF9100]',
  disconnected: 'bg-[#8899AA]/15 text-[#8899AA]'
}

const TILE_BASE = 'p-3.5 rounded-xl border flex flex-col items-center justify-center gap-2 transition disabled:opacity-50 disabled:cursor-not-allowed'
const TILE_IDLE = 'bg-[#1A2634] border-[#243447] text-[#8899AA] hover:text-white hover:bg-[#223142]'

export const RemoteControls: React.FC<RemoteControlsProps> = ({ client, status, connection, onStatus }) => {
  const [pending, setPending] = useState<Action | null>(null)
  const [optimistic, setOptimistic] = useState<Optimistic>({})
  const [error, setError] = useState<string | null>(null)
  /** A quality change the phone accepted but has not reported yet (it applies at the next segment). */
  const [queuedQuality, setQueuedQuality] = useState<{ requested: VideoQuality; reported: VideoQuality } | null>(null)
  const clientRef = useRef(client)
  useEffect(() => {
    clientRef.current = client
  }, [client])

  const online = connection === 'online' && client !== null
  const disabledReason = connection === 'online' ? undefined : DISABLED_REASONS[connection]
  const disabled = !online || pending !== null

  const flashlightOn = optimistic.flashlight ?? status?.flashlight ?? false
  const sirenActive = optimistic.sirenActive ?? status?.sirenActive ?? false
  // Keep the user's choice until the phone reports something other than the value it had before.
  const waitingForQuality =
    queuedQuality !== null && status?.videoQuality === queuedQuality.reported ? queuedQuality.requested : null
  const quality = optimistic.videoQuality ?? waitingForQuality ?? status?.videoQuality ?? ''

  const run = async (
    action: Action,
    guess: Optimistic,
    call: (api: PerchClient) => Promise<Status>,
    onSuccess?: (result: Status) => void
  ) => {
    if (!client) return
    setPending(action)
    setOptimistic(guess)
    setError(null)
    try {
      const result = await call(client)
      // The connection may have been switched while this was in flight; that answer is from another phone.
      if (clientRef.current !== client) return
      onStatus(result)
      onSuccess?.(result)
    } catch (err) {
      if (clientRef.current === client) setError(describeError(err))
    } finally {
      setOptimistic({})
      setPending(null)
    }
  }

  const toggleFlashlight = () =>
    run('flashlight', { flashlight: !flashlightOn }, api => api.setFlashlight(!flashlightOn))

  const toggleSiren = () =>
    run('siren', { sirenActive: !sirenActive }, api =>
      sirenActive ? api.stopSiren() : api.startSiren(SIREN_SECONDS)
    )

  const flipCamera = () => run('flip', {}, api => api.flipCamera())

  const changeQuality = (next: VideoQuality) =>
    run(
      'quality',
      { videoQuality: next },
      api => api.setQuality(next),
      result => setQueuedQuality(result.videoQuality === next ? null : { requested: next, reported: result.videoQuality })
    )

  const backup = status?.backup ?? null
  const storage = status?.storage ?? null
  const quota: QuotaView = backup ? describeQuota(backup) : { kind: 'unavailable' }

  return (
    <div className="bg-[#141F2B] border border-[#1A2634] rounded-2xl p-5 shadow-xl space-y-5">
      <div>
        <h2 className="text-base font-bold text-white tracking-wide">Remote Controls</h2>
        <p className="text-xs text-[#8899AA]">Manage phone camera functions remotely</p>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <button
          onClick={toggleFlashlight}
          disabled={disabled}
          aria-pressed={flashlightOn}
          title={disabledReason ?? 'Turn the phone flashlight on or off'}
          className={`${TILE_BASE} ${
            flashlightOn ? 'bg-[#2979FF]/20 border-[#2979FF] text-[#2979FF]' : TILE_IDLE
          }`}
        >
          <Flashlight className="w-5 h-5" />
          <span className="text-xs font-semibold">Flashlight</span>
        </button>

        <button
          onClick={toggleSiren}
          disabled={disabled}
          aria-pressed={sirenActive}
          title={disabledReason ?? (sirenActive ? 'Stop the siren' : `Sound the siren for ${SIREN_SECONDS} seconds`)}
          className={`${TILE_BASE} ${
            sirenActive
              ? 'bg-[#FF1744]/20 border-[#FF1744] text-[#FF1744] animate-pulse'
              : `${TILE_IDLE} hover:text-[#FF1744]`
          }`}
        >
          <BellRing className="w-5 h-5" />
          <span className="text-xs font-semibold">{sirenActive ? 'Stop siren' : 'Sound siren'}</span>
        </button>

        <button
          onClick={flipCamera}
          disabled={disabled}
          aria-label="Flip camera"
          title={disabledReason ?? 'Switch between the back and front camera'}
          className={`${TILE_BASE} ${TILE_IDLE}`}
        >
          <SwitchCamera className="w-5 h-5" />
          <span className="text-xs font-semibold">Flip camera</span>
          {status && <span className="text-[10px] text-[#8899AA] -mt-1.5 capitalize">{status.camera}</span>}
        </button>

        <div
          title={disabledReason}
          className="p-3.5 rounded-xl border bg-[#1A2634] border-[#243447] flex flex-col items-center justify-center gap-1.5"
        >
          <Sliders className="w-5 h-5 text-[#8899AA]" />
          <select
            value={quality}
            onChange={e => changeQuality(e.target.value as VideoQuality)}
            disabled={disabled}
            aria-label="Video quality"
            className="bg-transparent text-xs font-semibold text-white focus:outline-none cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {quality === '' && (
              <option value="" disabled className="bg-[#1A2634]">
                --
              </option>
            )}
            {VIDEO_QUALITIES.map(option => (
              <option key={option} value={option} className="bg-[#1A2634]">
                {QUALITY_LABELS[option]}
              </option>
            ))}
          </select>
        </div>
      </div>

      {waitingForQuality && !optimistic.videoQuality && (
        <p className="text-[11px] text-[#8899AA]">Quality applies from the next recording segment.</p>
      )}

      {status?.throttled && (
        <p className="text-xs text-[#FF9100] bg-[#FF9100]/10 border border-[#FF9100]/30 rounded-lg px-3 py-2">
          The phone is overheating and is running at reduced quality.
        </p>
      )}

      {error && (
        <p role="alert" className="text-xs text-[#FF8A9B] bg-[#FF1744]/10 border border-[#FF1744]/30 rounded-lg px-3 py-2">
          {error}
        </p>
      )}

      {/* Cloud backup status */}
      <div className="p-4 bg-[#1A2634] rounded-xl border border-[#243447]/80 space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div
              className={`w-7 h-7 rounded-lg flex items-center justify-center ${
                backup ? BACKUP_ICON_CLASSES[backup.state] : BACKUP_ICON_CLASSES.disconnected
              }`}
            >
              {BACKUP_ICONS[backup?.state ?? 'disconnected']}
            </div>
            <div>
              <p className="text-xs font-semibold text-white">Google Drive Backup</p>
              <p className="text-[11px] text-[#8899AA]">
                {!backup && 'Waiting for the phone...'}
                {backup && backup.state === 'connected' && backup.account}
                {backup && backup.state === 'needs_auth' && 'Sign in again on the phone'}
                {backup && backup.state === 'disconnected' && 'Connect an account on the phone'}
              </p>
            </div>
          </div>
          {backup && (
            <span
              className={`text-[10px] font-mono font-bold px-2 py-0.5 rounded-full border ${BACKUP_BADGES[backup.state].classes}`}
            >
              {BACKUP_BADGES[backup.state].label}
            </span>
          )}
        </div>

        {backup && (
          <>
            <div className="flex justify-between text-[11px] text-[#8899AA]">
              <span>{backup.autoUpload ? `Auto-sync every ${backup.intervalMinutes} min` : 'Auto-sync is off'}</span>
              <span className="font-mono text-white">
                {backup.pending} pending / {backup.uploaded} uploaded
              </span>
            </div>

            <div className="space-y-1.5">
              <div className="flex justify-between text-[11px]">
                <span className="text-[#8899AA]">Cloud Quota</span>
                {quota.kind === 'unavailable' ? (
                  <span className="text-[#8899AA]">Quota unavailable</span>
                ) : (
                  <span className="text-white font-mono">{quota.text}</span>
                )}
              </div>
              {quota.kind === 'limited' && (
                <div
                  role="progressbar"
                  aria-label="Cloud quota used"
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={quota.percent}
                  className="w-full bg-[#0F1923] h-1.5 rounded-full overflow-hidden"
                >
                  <div className="bg-[#2979FF] h-full rounded-full" style={{ width: `${quota.percent}%` }} />
                </div>
              )}
            </div>
          </>
        )}
      </div>

      {/* Local storage */}
      {storage && (
        <div className="p-4 bg-[#1A2634] rounded-xl border border-[#243447]/80 space-y-1.5">
          <div className="flex items-center justify-between text-[11px]">
            <span className="flex items-center gap-1.5 text-[#8899AA]">
              <HardDrive className="w-3.5 h-3.5" />
              Phone storage
            </span>
            <span className="text-white font-mono">
              {formatBytes(storage.usedBytes)}
              {storage.limitBytes > 0 ? ` / ${formatBytes(storage.limitBytes)}` : ' used'}
            </span>
          </div>
          <p className="text-[11px] text-[#8899AA]">{formatBytes(storage.freeBytes)} free on device</p>
        </div>
      )}
    </div>
  )
}

type QuotaView =
  | { kind: 'limited'; text: string; percent: number }
  | { kind: 'unlimited'; text: string }
  | { kind: 'unavailable' }

/** A null total with a known usage means Drive reports no limit (unlimited plan). */
function describeQuota(backup: BackupInfo): QuotaView {
  const { quotaUsedBytes: used, quotaTotalBytes: total } = backup
  if (used === null) return { kind: 'unavailable' }
  if (total === null) return { kind: 'unlimited', text: `${formatBytes(used)} / Unlimited` }
  if (total <= 0) return { kind: 'unavailable' }
  return {
    kind: 'limited',
    text: `${formatBytes(used)} / ${formatBytes(total)}`,
    percent: Math.min(100, Math.round((used / total) * 100))
  }
}
