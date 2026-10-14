export interface PerchConfig {
  controlUrl: string
  token: string
  whepUrl: string
}

export type ConfigErrors = Partial<Record<keyof PerchConfig, string>>

export const STORAGE_KEY = 'perch.viewer.config.v1'

export const EMPTY_CONFIG: PerchConfig = { controlUrl: '', token: '', whepUrl: '' }

const TOKEN_PATTERN = /^[\x21-\x7E]+$/

type StorageLike = Pick<Storage, 'getItem' | 'setItem'>
type LocationLike = Pick<Location, 'search' | 'hash'>

export function parseHttpUrl(input: string): URL | null {
  try {
    const url = new URL(input.trim())
    return url.protocol === 'http:' || url.protocol === 'https:' ? url : null
  } catch {
    return null
  }
}

export function normalizeControlUrl(input: string): string {
  return input.trim().replace(/\/+$/, '')
}

export function validateConfig(config: PerchConfig): ConfigErrors {
  const errors: ConfigErrors = {}
  const control = config.controlUrl.trim()
  const whep = config.whepUrl.trim()
  const token = config.token.trim()

  if (!control) errors.controlUrl = 'Enter the phone control URL.'
  else if (!parseHttpUrl(control)) errors.controlUrl = 'Must be a valid http:// or https:// URL.'

  if (!token) errors.token = 'Enter the access token shown in the Perch app.'
  else if (!TOKEN_PATTERN.test(token)) errors.token = 'Token may only contain visible ASCII characters, no spaces.'

  if (whep && !parseHttpUrl(whep)) errors.whepUrl = 'Must be a valid http:// or https:// URL.'

  return errors
}

export function isConfigured(config: PerchConfig): boolean {
  return Boolean(config.controlUrl && config.token)
}

export function sanitizeConfig(config: PerchConfig): PerchConfig {
  return {
    controlUrl: normalizeControlUrl(config.controlUrl),
    token: config.token.trim(),
    whepUrl: config.whepUrl.trim(),
  }
}

/**
 * Reads initial control/WHEP URLs from `?control=...&whep=...` or the same
 * keys in the hash. The access token is deliberately never read from the URL.
 */
export function readUrlOverrides(location: LocationLike): Partial<PerchConfig> {
  const params = new URLSearchParams(location.search)
  new URLSearchParams(location.hash.replace(/^#/, '')).forEach((value, key) => {
    if (!params.has(key)) params.set(key, value)
  })

  const overrides: Partial<PerchConfig> = {}
  const control = params.get('control')
  const whep = params.get('whep')
  if (control && parseHttpUrl(control)) overrides.controlUrl = normalizeControlUrl(control)
  if (whep && parseHttpUrl(whep)) overrides.whepUrl = whep.trim()
  return overrides
}

function defaultStorage(): StorageLike | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

function defaultLocation(): LocationLike | null {
  return typeof globalThis.location === 'undefined' ? null : globalThis.location
}

function readStored(storage: StorageLike | null): PerchConfig {
  if (!storage) return EMPTY_CONFIG
  try {
    const raw = storage.getItem(STORAGE_KEY)
    if (!raw) return EMPTY_CONFIG
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return EMPTY_CONFIG
    const record = parsed as Record<string, unknown>
    const text = (key: string) => (typeof record[key] === 'string' ? (record[key] as string) : '')
    return { controlUrl: text('controlUrl'), token: text('token'), whepUrl: text('whepUrl') }
  } catch {
    return EMPTY_CONFIG
  }
}

export interface LoadedConfig {
  /** What is saved in this browser. Only this is ever used to talk to the phone. */
  config: PerchConfig
  /**
   * Values from the page URL that need the user's confirmation in the Settings
   * dialog before they replace `config`, or null when there is nothing to confirm.
   */
  prefill: PerchConfig | null
}

function originOf(url: string): string | null {
  return parseHttpUrl(url)?.origin ?? null
}

/**
 * Loads saved settings. `?control=` / `?whep=` values never take effect by
 * themselves: they only pre-fill the Settings dialog, and the stored token is
 * carried over only when the control URL keeps the same origin, so a crafted
 * link can never send the token to another host.
 */
export function loadConfig(
  storage: StorageLike | null = defaultStorage(),
  location: LocationLike | null = defaultLocation(),
): LoadedConfig {
  const config = readStored(storage)
  const overrides = location ? readUrlOverrides(location) : {}

  const controlUrl = overrides.controlUrl ?? config.controlUrl
  const whepUrl = overrides.whepUrl ?? config.whepUrl
  const unchanged = controlUrl === config.controlUrl && whepUrl === config.whepUrl
  if (unchanged) return { config, prefill: null }

  const sameOrigin = config.controlUrl !== '' && originOf(controlUrl) === originOf(config.controlUrl)
  return { config, prefill: { controlUrl, whepUrl, token: sameOrigin ? config.token : '' } }
}

/** Removes control/whep (and any stray token) from the address bar without adding a history entry. */
export function stripUrlOverrides(
  location: Pick<Location, 'pathname' | 'search' | 'hash'>,
  history: Pick<History, 'replaceState'>,
): void {
  const strip = (raw: string, prefix: string) => {
    const params = new URLSearchParams(raw.replace(/^[?#]/, ''))
    const had = ['control', 'whep', 'token'].filter(key => params.has(key))
    had.forEach(key => params.delete(key))
    const rest = params.toString()
    if (had.length === 0) return { changed: false, text: raw }
    return { changed: true, text: rest ? `${prefix}${rest}` : '' }
  }
  const search = strip(location.search, '?')
  const hash = strip(location.hash, '#')
  if (search.changed || hash.changed) {
    history.replaceState(null, '', `${location.pathname}${search.text}${hash.text}`)
  }
}

/** Returns false when persistence is unavailable (private mode, blocked storage, quota). */
export function saveConfig(config: PerchConfig, storage: StorageLike | null = defaultStorage()): boolean {
  if (!storage) return false
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify(sanitizeConfig(config)))
    return true
  } catch {
    return false
  }
}
