import { useCallback, useEffect, useState } from 'react'
import { LivePlayer } from './components/LivePlayer'
import { EventFeed } from './components/EventFeed'
import { RemoteControls } from './components/RemoteControls'
import { SettingsDialog } from './components/SettingsDialog'
import { usePerchStatus, type Connection } from './hooks/usePerchStatus'
import { EMPTY_CONFIG, isConfigured, loadConfig, saveConfig, stripUrlOverrides, type PerchConfig } from './lib/config'
import type { BackupInfo, Status } from './lib/api'
import {
  Eye,
  Smartphone,
  Battery,
  BatteryCharging,
  Thermometer,
  Cloud,
  ExternalLink,
  Settings,
  AlertTriangle
} from 'lucide-react'

const PILLS: Record<Connection, { label: string; text: string; border: string; bg: string; dot: string; pulse: boolean }> = {
  online: { label: 'SYSTEM ONLINE', text: 'text-[#00E676]', border: 'border-[#00E676]/30', bg: 'bg-[#00E676]/10', dot: 'bg-[#00E676]', pulse: true },
  connecting: { label: 'CONNECTING', text: 'text-[#FF9100]', border: 'border-[#FF9100]/30', bg: 'bg-[#FF9100]/10', dot: 'bg-[#FF9100]', pulse: true },
  offline: { label: 'OFFLINE', text: 'text-[#FF1744]', border: 'border-[#FF1744]/30', bg: 'bg-[#FF1744]/10', dot: 'bg-[#FF1744]', pulse: false },
  rate_limited: { label: 'TOO MANY ATTEMPTS', text: 'text-[#FF9100]', border: 'border-[#FF9100]/30', bg: 'bg-[#FF9100]/10', dot: 'bg-[#FF9100]', pulse: false },
  unauthorized: { label: 'SIGN-IN FAILED', text: 'text-[#FF1744]', border: 'border-[#FF1744]/30', bg: 'bg-[#FF1744]/10', dot: 'bg-[#FF1744]', pulse: false },
  unconfigured: { label: 'NOT CONFIGURED', text: 'text-[#8899AA]', border: 'border-[#8899AA]/30', bg: 'bg-[#8899AA]/10', dot: 'bg-[#8899AA]', pulse: false }
}

function backupSummary(backup: BackupInfo): string {
  if (backup.state === 'needs_auth') return 'Sign-in required'
  if (backup.state === 'disconnected') return 'Not connected'
  return backup.autoUpload ? 'Google Drive (Active)' : 'Google Drive (Paused)'
}

function detectionSummary(status: Status): string {
  const faces = `${status.faceCount} ${status.faceCount === 1 ? 'face' : 'faces'}`
  return `${status.motion ? 'Motion detected' : 'No motion'} • ${faces}`
}

const LINK_NOTICE =
  'These values came from a link, not from you. Only save them if you trust the sender; the access token has to be entered again.'

export function App() {
  const [loaded] = useState(() => loadConfig())
  // `config` is what is saved and used to talk to the phone; `prefill` only seeds the dialog until confirmed.
  const [config, setConfig] = useState<PerchConfig>(loaded.config)
  const [prefill, setPrefill] = useState<PerchConfig | null>(loaded.prefill)
  const [settingsOpen, setSettingsOpen] = useState(() => !isConfigured(loaded.config) || loaded.prefill !== null)
  const [saveFailed, setSaveFailed] = useState(false)
  // Nothing connects (no polling, no stream) while link-supplied values wait for confirmation.
  const active = prefill ? EMPTY_CONFIG : config
  const { client, status, events, connection, error, applyStatus } = usePerchStatus(active)

  useEffect(() => {
    stripUrlOverrides(window.location, window.history)
  }, [])

  const closeSettings = useCallback(() => {
    setPrefill(null)
    setSettingsOpen(false)
  }, [])
  const saveSettings = (next: PerchConfig) => {
    setSaveFailed(!saveConfig(next))
    setConfig(next)
    setPrefill(null)
    setSettingsOpen(false)
  }

  const pill = PILLS[connection]

  return (
    <>
    <div inert={settingsOpen} className="min-h-screen bg-[#0F1923] text-white flex flex-col font-sans">
      {/* Top Header */}
      <header className="border-b border-[#1A2634] bg-[#141F2B]/90 backdrop-blur-md sticky top-0 z-50 px-6 py-3.5 flex items-center justify-between">
        {/* Brand */}
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-[#2979FF] flex items-center justify-center shadow-lg shadow-[#2979FF]/25">
            <Eye className="w-5 h-5 text-white" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-lg font-black tracking-tight text-white">PERCH</h1>
              <span className="text-[10px] uppercase font-bold tracking-widest px-2 py-0.5 rounded bg-[#2979FF]/20 text-[#2979FF] border border-[#2979FF]/30">
                PRO
              </span>
            </div>
            <p className="text-xs text-[#8899AA]">Intelligent Smartphone Surveillance</p>
          </div>
        </div>

        {/* Device & Status Bar */}
        <div className="flex items-center gap-4">
          {status && (
            <div className="hidden md:flex items-center gap-2 bg-[#0F1923] border border-[#1A2634] px-3 py-1.5 rounded-xl text-xs">
              <Smartphone className="w-3.5 h-3.5 text-[#2979FF]" />
              <span className="font-medium text-white">{status.deviceName}</span>
            </div>
          )}

          {/* Battery & Thermal stats */}
          {status?.battery && (
            <div className="hidden sm:flex items-center gap-3 text-xs bg-[#0F1923] border border-[#1A2634] px-3 py-1.5 rounded-xl font-mono text-[#8899AA]">
              <div className="flex items-center gap-1.5 text-[#00E676]">
                {status.battery.charging ? (
                  <BatteryCharging className="w-3.5 h-3.5" aria-label="Charging" />
                ) : (
                  <Battery className="w-3.5 h-3.5" aria-label="On battery" />
                )}
                <span>{status.battery.percent === null ? '--' : `${Math.round(status.battery.percent)}%`}</span>
              </div>
              <span className="text-white/20">|</span>
              <div className="flex items-center gap-1.5 text-[#5393FF]">
                <Thermometer className="w-3.5 h-3.5" />
                <span>{status.battery.temperatureC === null ? '--' : `${status.battery.temperatureC.toFixed(1)}°C`}</span>
              </div>
            </div>
          )}

          {/* System status pill */}
          <div className={`flex items-center gap-2 border px-3 py-1.5 rounded-xl text-xs font-bold ${pill.bg} ${pill.border} ${pill.text}`}>
            <span className={`w-2 h-2 rounded-full ${pill.dot} ${pill.pulse ? 'animate-pulse' : ''}`}></span>
            <span>{pill.label}</span>
          </div>

          <button
            onClick={() => setSettingsOpen(true)}
            aria-label="Settings"
            title="Connection settings"
            className="w-9 h-9 rounded-xl bg-[#0F1923] border border-[#1A2634] hover:border-[#2979FF] text-[#8899AA] hover:text-white flex items-center justify-center transition"
          >
            <Settings className="w-4 h-4" />
          </button>
        </div>
      </header>

      {/* Main Dashboard Content */}
      <main className="flex-1 max-w-7xl w-full mx-auto p-6 grid grid-cols-1 lg:grid-cols-3 gap-6">
        {(saveFailed || (error && connection !== 'online')) && (
          <div className="lg:col-span-3 space-y-2">
            {saveFailed && (
              <p role="alert" className="flex items-center gap-2 text-xs text-[#FF9100] bg-[#FF9100]/10 border border-[#FF9100]/30 rounded-lg px-3 py-2">
                <AlertTriangle className="w-4 h-4 shrink-0" />
                Settings could not be saved in this browser. You will need to enter them again next time.
              </p>
            )}
            {error && connection !== 'online' && (
              <p role="status" className="text-xs text-[#8899AA] bg-[#141F2B] border border-[#1A2634] rounded-lg px-3 py-2">
                {error}
              </p>
            )}
          </div>
        )}

        {/* Left Column: Live Player (Takes 2 Columns on Large Screens) */}
        <div className="lg:col-span-2 space-y-6">
          <LivePlayer whepUrl={active.whepUrl} status={status} connection={connection} />

          {/* Camera Info and Quick Stats Banner */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <InfoCard label="Storage Mode">
              {status
                ? status.recording
                  ? `Continuous (${status.segmentMinutes}m chunks)`
                  : 'Not recording'
                : '--'}
            </InfoCard>
            <InfoCard label="Video">
              {status ? `${status.videoQuality} • ${status.camera === 'back' ? 'Back' : 'Front'} camera` : '--'}
            </InfoCard>
            <InfoCard label="Detection">{status ? detectionSummary(status) : '--'}</InfoCard>
            <InfoCard label="Auto-Backup">{status?.backup ? backupSummary(status.backup) : '--'}</InfoCard>
          </div>
        </div>

        {/* Right Column: Events Feed & Remote Controls */}
        <div className="space-y-6">
          <RemoteControls client={client} status={status} connection={connection} onStatus={applyStatus} />
          <EventFeed events={events} connection={connection} />
        </div>
      </main>

      {/* Footer */}
      <footer className="border-t border-[#1A2634] bg-[#141F2B]/60 px-6 py-4 text-xs text-[#8899AA] flex flex-col sm:flex-row items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <span>Perch Security System v1.0.0</span>
          <span>•</span>
          <span>Zero Subscription Surveillance</span>
        </div>
        <div className="flex items-center gap-4">
          <a
            href="https://drive.google.com"
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-1 hover:text-white transition"
          >
            <Cloud className="w-3.5 h-3.5 text-[#2979FF]" />
            <span>Open Google Drive</span>
            <ExternalLink className="w-3 h-3 ml-0.5" />
          </a>
        </div>
      </footer>

    </div>

    {settingsOpen && (
      <SettingsDialog
        config={prefill ?? config}
        dismissible={isConfigured(config)}
        notice={prefill ? LINK_NOTICE : undefined}
        onSave={saveSettings}
        onClose={closeSettings}
      />
    )}
    </>
  )
}

function InfoCard({ label, children }: { label: string; children: string }) {
  return (
    <div className="p-4 bg-[#141F2B] border border-[#1A2634] rounded-2xl">
      <span className="text-xs text-[#8899AA]">{label}</span>
      <p className="text-sm font-bold text-white mt-1">{children}</p>
    </div>
  )
}

export default App
