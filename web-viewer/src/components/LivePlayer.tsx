import React, { useCallback, useEffect, useRef, useState } from 'react'
import {
  Play,
  Pause,
  Volume2,
  VolumeX,
  Maximize,
  Camera,
  Wifi,
  VideoOff,
  Loader2,
  ShieldAlert,
  Flame
} from 'lucide-react'
import { describeError, type Status } from '../lib/api'
import { formatBitrate, formatClock } from '../lib/format'
import { containRect, type Rect } from '../lib/geometry'
import { saveSnapshot } from '../lib/snapshot'
import type { StreamStats } from '../lib/rtcStats'
import { useWhepStream, type StreamState } from '../hooks/useWhepStream'
import type { Connection } from '../hooks/usePerchStatus'

interface LivePlayerProps {
  /** WHEP endpoint of the stream; empty when not configured. */
  whepUrl: string
  status: Status | null
  connection: Connection
}

interface Size {
  width: number
  height: number
}

const STATE_BADGE: Record<StreamState, { label: string; dot: string }> = {
  live: { label: 'LIVE', dot: 'bg-[#00E676] animate-pulse' },
  connecting: { label: 'CONNECTING', dot: 'bg-[#FF9100] animate-pulse' },
  reconnecting: { label: 'RECONNECTING', dot: 'bg-[#FF9100] animate-pulse' },
  unconfigured: { label: 'NOT CONFIGURED', dot: 'bg-[#8899AA]' }
}

function formatStats(stats: StreamStats): string {
  const parts: string[] = []
  if (stats.width && stats.height) parts.push(`${stats.width}x${stats.height}`)
  if (stats.fps !== null) parts.push(`${Math.round(stats.fps)} fps`)
  if (stats.bitrateBps !== null) parts.push(formatBitrate(stats.bitrateBps))
  return parts.join(' • ')
}

const CONTROL_BUTTON =
  'w-9 h-9 rounded-full bg-white/10 hover:bg-white/20 disabled:opacity-40 disabled:hover:bg-white/10 text-white flex items-center justify-center transition backdrop-blur-md'

export const LivePlayer: React.FC<LivePlayerProps> = ({ whepUrl, status, connection }) => {
  const { stream, state, stats, error } = useWhepStream(whepUrl || null)
  const [playing, setPlaying] = useState(false)
  const [muted, setMuted] = useState(true)
  const [videoSize, setVideoSize] = useState<Size>({ width: 0, height: 0 })
  const [containerSize, setContainerSize] = useState<Size>({ width: 0, height: 0 })
  const [snapshotNote, setSnapshotNote] = useState<{ text: string; failed: boolean } | null>(null)

  const containerRef = useRef<HTMLDivElement>(null)
  const videoRef = useRef<HTMLVideoElement>(null)

  useEffect(() => {
    const video = videoRef.current
    if (video) video.srcObject = stream
  }, [stream])

  useEffect(() => {
    const video = videoRef.current
    if (video) video.muted = muted
  }, [muted])

  useEffect(() => {
    const container = containerRef.current
    if (!container || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(entries => {
      const { width, height } = entries[0].contentRect
      setContainerSize({ width, height })
    })
    observer.observe(container)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    if (!snapshotNote) return
    const timer = setTimeout(() => setSnapshotNote(null), 4000)
    return () => clearTimeout(timer)
  }, [snapshotNote])

  const syncVideoSize = useCallback(() => {
    const video = videoRef.current
    if (video) setVideoSize({ width: video.videoWidth, height: video.videoHeight })
  }, [])

  const togglePlay = () => {
    const video = videoRef.current
    if (!video) return
    if (video.paused) video.play().catch(() => setPlaying(false))
    else video.pause()
  }

  const toggleFullscreen = () => {
    const container = containerRef.current
    if (!container) return
    if (!document.fullscreenElement) {
      container.requestFullscreen().catch(() => undefined)
    } else {
      document.exitFullscreen().catch(() => undefined)
    }
  }

  const takeSnapshot = async () => {
    const video = videoRef.current
    if (!video) return
    try {
      const filename = await saveSnapshot(video)
      setSnapshotNote({ text: `Saved ${filename} to your downloads`, failed: false })
    } catch (err) {
      setSnapshotNote({ text: describeError(err), failed: true })
    }
  }

  // Status overlays describe the phone right now, so stale data is never drawn.
  const live = connection === 'online' ? status : null
  const badge = STATE_BADGE[state]
  const hasVideo = stream !== null
  const overlay: Rect | null =
    containerSize.width > 0 && videoSize.width > 0
      ? containRect(containerSize.width, containerSize.height, videoSize.width, videoSize.height)
      : null

  return (
    <div
      ref={containerRef}
      className="relative w-full aspect-video bg-[#0B131B] rounded-2xl overflow-hidden border border-[#1A2634] shadow-2xl group select-none"
    >
      <video
        ref={videoRef}
        autoPlay
        playsInline
        muted
        onPlaying={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onLoadedMetadata={syncVideoSize}
        onResize={syncVideoSize}
        aria-label="Live camera stream"
        className={`absolute inset-0 w-full h-full object-contain bg-black ${hasVideo ? '' : 'invisible'}`}
      />

      {!hasVideo && (
        <div className="absolute inset-0 flex flex-col items-center justify-center bg-gradient-to-b from-[#141F2B] via-[#0F1923] to-[#0B131B] text-center px-6">
          <div className="inline-flex items-center justify-center w-20 h-20 rounded-full bg-[#1A2634]/80 border border-[#2979FF]/40 mb-3">
            {state === 'connecting' ? (
              <Loader2 className="w-10 h-10 text-[#2979FF] animate-spin" />
            ) : (
              <VideoOff className="w-10 h-10 text-[#8899AA]" />
            )}
          </div>
          <p className="text-sm font-semibold text-white/90 tracking-wide">
            {state === 'unconfigured' && 'No live stream configured'}
            {state === 'connecting' && 'Connecting to the stream...'}
            {state === 'reconnecting' && 'Stream offline, retrying...'}
            {state === 'live' && 'Waiting for video...'}
          </p>
          <p className="text-xs text-white/50 mt-1 max-w-sm">
            {state === 'unconfigured' ? 'Open Settings and enter the live stream (WHEP) URL.' : error}
          </p>
        </div>
      )}

      {/* Overlay layer aligned to the actual picture, accounting for letterboxing */}
      {live && hasVideo && (
        <div
          className="absolute pointer-events-none"
          style={
            overlay
              ? { left: overlay.left, top: overlay.top, width: overlay.width, height: overlay.height }
              : { inset: 0 }
          }
        >
          {live.faces.map((face, index) => (
            <div
              key={index}
              data-testid="face-box"
              style={{
                left: `${face.x * 100}%`,
                top: `${face.y * 100}%`,
                width: `${face.w * 100}%`,
                height: `${face.h * 100}%`
              }}
              className="absolute border-2 border-[#00BCD4] rounded-lg bg-[#00BCD4]/10 shadow-[0_0_15px_rgba(0,188,212,0.4)] transition-all duration-500"
            >
              <span className="absolute -top-6 left-0 bg-[#00BCD4] text-[#0F1923] text-[10px] font-bold px-1.5 py-0.5 rounded tracking-wider shadow">
                FACE
              </span>
              <div className="absolute -bottom-1 -right-1 w-2 h-2 border-r-2 border-b-2 border-[#00BCD4]"></div>
              <div className="absolute -top-1 -left-1 w-2 h-2 border-l-2 border-t-2 border-[#00BCD4]"></div>
            </div>
          ))}
        </div>
      )}

      {/* Top Header Overlay */}
      <div className="absolute top-0 inset-x-0 p-4 flex items-start justify-between bg-gradient-to-b from-black/80 via-black/40 to-transparent pointer-events-none z-10">
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1.5 bg-black/60 backdrop-blur-md px-2.5 py-1 rounded-full border border-white/10">
            <span className={`w-2 h-2 rounded-full ${badge.dot}`}></span>
            <span className="text-xs font-extrabold tracking-wider text-white">{badge.label}</span>
          </div>

          {live?.recording && (
            <div className="flex items-center gap-1.5 bg-black/60 backdrop-blur-md px-2.5 py-1 rounded-full border border-white/10">
              <span className="w-2 h-2 rounded-full bg-[#FF1744]"></span>
              <span className="text-xs font-mono font-semibold text-white/90">
                REC {formatClock(live.recordingSeconds)}
              </span>
            </div>
          )}

          {live?.throttled && (
            <div className="flex items-center gap-1.5 bg-[#FF9100]/20 backdrop-blur-md px-2.5 py-1 rounded-full border border-[#FF9100] text-[#FF9100] text-xs font-bold">
              <Flame className="w-3 h-3" />
              THROTTLED
            </div>
          )}
        </div>

        <div className="flex flex-col items-end gap-2">
          {state === 'live' && stats && formatStats(stats) && (
            <div className="flex items-center gap-1.5 bg-black/60 backdrop-blur-md px-2.5 py-1 rounded-full border border-white/10 text-xs font-mono text-white/80">
              <Wifi className="w-3 h-3 text-[#00E676]" />
              <span>{formatStats(stats)}</span>
            </div>
          )}
          {live?.motion && (
            <div className="flex items-center gap-2 bg-[#FF9100]/20 border border-[#FF9100] text-[#FF9100] px-3 py-1 rounded-full text-xs font-bold animate-pulse">
              <ShieldAlert className="w-3.5 h-3.5" />
              MOTION DETECTED
            </div>
          )}
        </div>
      </div>

      {snapshotNote && (
        <div
          role={snapshotNote.failed ? 'alert' : 'status'}
          className={`absolute bottom-20 left-1/2 -translate-x-1/2 z-10 px-3 py-1.5 rounded-lg text-xs font-medium backdrop-blur-md border ${
            snapshotNote.failed
              ? 'bg-[#FF1744]/20 border-[#FF1744] text-[#FF8A9B]'
              : 'bg-black/70 border-white/10 text-white/90'
          }`}
        >
          {snapshotNote.text}
        </div>
      )}

      {/* Bottom Floating Control Bar */}
      <div className="absolute bottom-0 inset-x-0 p-4 bg-gradient-to-t from-black/85 via-black/40 to-transparent flex items-center justify-between opacity-90 group-hover:opacity-100 transition-opacity z-10">
        <div className="flex items-center gap-3">
          <button
            onClick={togglePlay}
            disabled={!hasVideo}
            className={CONTROL_BUTTON}
            aria-label={playing ? 'Pause' : 'Play'}
            title={playing ? 'Pause' : 'Play'}
          >
            {playing ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4 ml-0.5" />}
          </button>

          <button
            onClick={() => setMuted(!muted)}
            className={CONTROL_BUTTON}
            aria-label={muted ? 'Unmute' : 'Mute'}
            title={muted ? 'Unmute' : 'Mute'}
          >
            {muted ? <VolumeX className="w-4 h-4" /> : <Volume2 className="w-4 h-4" />}
          </button>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={takeSnapshot}
            disabled={!hasVideo}
            title={hasVideo ? 'Save the current frame as a PNG' : 'No video to capture'}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-[#2979FF] hover:bg-[#5393FF] disabled:opacity-40 disabled:hover:bg-[#2979FF] text-white text-xs font-semibold shadow-lg shadow-[#2979FF]/20 transition"
          >
            <Camera className="w-3.5 h-3.5" />
            <span>Snapshot</span>
          </button>

          <button
            onClick={toggleFullscreen}
            className={CONTROL_BUTTON}
            aria-label="Fullscreen"
            title="Fullscreen"
          >
            <Maximize className="w-4 h-4" />
          </button>
        </div>
      </div>
    </div>
  )
}
