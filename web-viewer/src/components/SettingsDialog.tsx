import React, { useEffect, useId, useRef, useState } from 'react'
import { X, Loader2, CheckCircle2, AlertTriangle } from 'lucide-react'
import { PerchClient, describeError } from '../lib/api'
import {
  sanitizeConfig,
  validateConfig,
  type ConfigErrors,
  type PerchConfig
} from '../lib/config'

interface SettingsDialogProps {
  config: PerchConfig
  /** The dialog cannot be dismissed until a working configuration exists. */
  dismissible: boolean
  /** Shown above the form, e.g. to flag values that came from a link rather than the user. */
  notice?: string
  onSave: (config: PerchConfig) => void
  onClose: () => void
}

type TestResult = { ok: true; message: string } | { ok: false; message: string } | null

const FOCUSABLE = 'button, input, select, textarea, a[href], [tabindex]:not([tabindex="-1"])'

const INPUT_CLASSES =
  'w-full bg-[#0F1923] border border-[#243447] focus:border-[#2979FF] focus:outline-none rounded-lg px-3 py-2 text-sm text-white placeholder:text-[#8899AA]/60'

export const SettingsDialog: React.FC<SettingsDialogProps> = ({ config, dismissible, notice, onSave, onClose }) => {
  const [draft, setDraft] = useState<PerchConfig>(config)
  const [errors, setErrors] = useState<ConfigErrors>({})
  const [testing, setTesting] = useState(false)
  const [result, setResult] = useState<TestResult>(null)
  const titleId = useId()
  const firstFieldRef = useRef<HTMLInputElement>(null)
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    firstFieldRef.current?.focus()
    return () => opener?.focus()
  }, [])

  useEffect(() => {
    if (!dismissible) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [dismissible, onClose])

  // Keep Tab / Shift+Tab inside the dialog while it is open.
  const trapTab = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Tab' || !dialogRef.current) return
    const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
      element => !element.hasAttribute('disabled')
    )
    if (focusable.length === 0) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    const active = document.activeElement
    if (event.shiftKey && (active === first || !dialogRef.current.contains(active))) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && (active === last || !dialogRef.current.contains(active))) {
      event.preventDefault()
      first.focus()
    }
  }

  const update = (field: keyof PerchConfig) => (event: React.ChangeEvent<HTMLInputElement>) => {
    setDraft({ ...draft, [field]: event.target.value })
    setResult(null)
  }

  const validate = (): PerchConfig | null => {
    const found = validateConfig(draft)
    setErrors(found)
    return Object.keys(found).length === 0 ? sanitizeConfig(draft) : null
  }

  const testConnection = async () => {
    const valid = validate()
    if (!valid) return
    setTesting(true)
    setResult(null)
    try {
      const status = await new PerchClient(valid).getStatus()
      setResult({ ok: true, message: `Connected to ${status.deviceName}.` })
    } catch (err) {
      setResult({ ok: false, message: describeError(err) })
    } finally {
      setTesting(false)
    }
  }

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const valid = validate()
    if (valid) onSave(valid)
  }

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
      <div
        ref={dialogRef}
        onKeyDown={trapTab}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="w-full max-w-md bg-[#141F2B] border border-[#1A2634] rounded-2xl shadow-2xl"
      >
        <form onSubmit={submit} noValidate className="p-6 space-y-4">
          <div className="flex items-start justify-between">
            <div>
              <h2 id={titleId} className="text-base font-bold text-white tracking-wide">
                Connection settings
              </h2>
              <p className="text-xs text-[#8899AA] mt-0.5">
                Both values are shown in the Perch app on the phone.
              </p>
            </div>
            {dismissible && (
              <button
                type="button"
                onClick={onClose}
                aria-label="Close settings"
                className="w-8 h-8 rounded-full hover:bg-white/10 text-[#8899AA] hover:text-white flex items-center justify-center transition"
              >
                <X className="w-4 h-4" />
              </button>
            )}
          </div>

          {notice && (
            <p className="flex items-start gap-2 text-xs rounded-lg px-3 py-2 border text-[#FF9100] bg-[#FF9100]/10 border-[#FF9100]/30">
              <AlertTriangle className="w-4 h-4 shrink-0" />
              <span>{notice}</span>
            </p>
          )}

          <Field label="Phone control URL" error={errors.controlUrl}>
            {id => (
              <input
                id={id}
                ref={firstFieldRef}
                type="url"
                inputMode="url"
                autoComplete="off"
                placeholder="http://192.168.1.50:8080"
                value={draft.controlUrl}
                onChange={update('controlUrl')}
                className={INPUT_CLASSES}
              />
            )}
          </Field>

          <Field label="Access token" error={errors.token}>
            {id => (
              <input
                id={id}
                type="password"
                autoComplete="off"
                value={draft.token}
                onChange={update('token')}
                className={INPUT_CLASSES}
              />
            )}
          </Field>

          <Field label="Live stream (WHEP) URL" error={errors.whepUrl}>
            {id => (
              <input
                id={id}
                type="url"
                inputMode="url"
                autoComplete="off"
                placeholder="http://192.168.1.100:8889/perch/whep"
                value={draft.whepUrl}
                onChange={update('whepUrl')}
                className={INPUT_CLASSES}
              />
            )}
          </Field>

          <p className="text-[11px] text-[#8899AA]">
            Settings are saved in this browser only. The token is never sent anywhere except to the phone.
          </p>

          {result && (
            <p
              role={result.ok ? 'status' : 'alert'}
              className={`flex items-start gap-2 text-xs rounded-lg px-3 py-2 border ${
                result.ok
                  ? 'text-[#00E676] bg-[#00E676]/10 border-[#00E676]/30'
                  : 'text-[#FF8A9B] bg-[#FF1744]/10 border-[#FF1744]/30'
              }`}
            >
              {result.ok ? (
                <CheckCircle2 className="w-4 h-4 shrink-0" />
              ) : (
                <AlertTriangle className="w-4 h-4 shrink-0" />
              )}
              <span>{result.message}</span>
            </p>
          )}

          <div className="flex items-center justify-end gap-2 pt-1">
            <button
              type="button"
              onClick={testConnection}
              disabled={testing}
              className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-[#1A2634] hover:bg-[#223142] border border-[#243447] text-xs font-semibold text-white disabled:opacity-50 transition"
            >
              {testing && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
              Test connection
            </button>
            <button
              type="submit"
              className="px-4 py-2 rounded-lg bg-[#2979FF] hover:bg-[#5393FF] text-xs font-semibold text-white shadow-lg shadow-[#2979FF]/20 transition"
            >
              Save
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

interface FieldProps {
  label: string
  error?: string
  children: (id: string) => React.ReactNode
}

const Field: React.FC<FieldProps> = ({ label, error, children }) => {
  const id = useId()
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-xs font-semibold text-white">
        {label}
      </label>
      {children(id)}
      {error && (
        <p role="alert" className="text-[11px] text-[#FF8A9B]">
          {error}
        </p>
      )}
    </div>
  )
}
