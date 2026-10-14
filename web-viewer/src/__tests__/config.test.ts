import { describe, it, expect, vi } from 'vitest'
import {
  EMPTY_CONFIG,
  STORAGE_KEY,
  isConfigured,
  loadConfig,
  readUrlOverrides,
  saveConfig,
  stripUrlOverrides,
  validateConfig,
  type PerchConfig
} from '../lib/config'

function memoryStorage(initial: Record<string, string> = {}) {
  const data = { ...initial }
  return {
    data,
    getItem: (key: string) => data[key] ?? null,
    setItem: (key: string, value: string) => {
      data[key] = value
    }
  }
}

const noLocation = { search: '', hash: '' }

const valid: PerchConfig = {
  controlUrl: 'http://192.168.1.50:8080',
  token: 'abc123',
  whepUrl: 'http://192.168.1.100:8889/perch/whep'
}

describe('validateConfig', () => {
  it('accepts a complete configuration', () => {
    expect(validateConfig(valid)).toEqual({})
  })

  it('requires control URL and token but not the WHEP URL', () => {
    expect(validateConfig(EMPTY_CONFIG)).toEqual({
      controlUrl: expect.any(String),
      token: expect.any(String)
    })
  })

  it('rejects non-http URLs and malformed values', () => {
    const errors = validateConfig({
      controlUrl: 'ftp://phone',
      token: 'has space',
      whepUrl: 'not a url'
    })
    expect(errors.controlUrl).toMatch(/http/)
    expect(errors.token).toMatch(/ASCII/)
    expect(errors.whepUrl).toMatch(/http/)
  })

  it('rejects host:port without a scheme', () => {
    expect(validateConfig({ ...valid, controlUrl: '192.168.1.50:8080' }).controlUrl).toBeDefined()
  })
})

describe('isConfigured', () => {
  it('needs both control URL and token', () => {
    expect(isConfigured(valid)).toBe(true)
    expect(isConfigured({ ...valid, token: '' })).toBe(false)
    expect(isConfigured({ ...valid, controlUrl: '' })).toBe(false)
  })
})

describe('saveConfig / loadConfig', () => {
  it('round-trips and normalizes the stored config', () => {
    const storage = memoryStorage()

    expect(
      saveConfig({ ...valid, controlUrl: ' http://192.168.1.50:8080/ ', token: ' abc123 ' }, storage)
    ).toBe(true)

    expect(JSON.parse(storage.data[STORAGE_KEY])).toEqual(valid)
    expect(loadConfig(storage, noLocation)).toEqual({ config: valid, prefill: null })
  })

  it('returns an empty config when nothing is stored or the data is corrupt', () => {
    expect(loadConfig(memoryStorage(), noLocation).config).toEqual(EMPTY_CONFIG)
    expect(loadConfig(memoryStorage({ [STORAGE_KEY]: '{not json' }), noLocation).config).toEqual(EMPTY_CONFIG)
    expect(loadConfig(memoryStorage({ [STORAGE_KEY]: '42' }), noLocation).config).toEqual(EMPTY_CONFIG)
    expect(loadConfig(memoryStorage({ [STORAGE_KEY]: '{"token":5}' }), noLocation).config).toEqual(EMPTY_CONFIG)
  })

  it('tolerates missing or throwing storage', () => {
    const throwing = {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('quota')
      }
    }
    expect(loadConfig(null, noLocation).config).toEqual(EMPTY_CONFIG)
    expect(loadConfig(throwing, noLocation).config).toEqual(EMPTY_CONFIG)
    expect(saveConfig(valid, null)).toBe(false)
    expect(saveConfig(valid, throwing)).toBe(false)
  })
})

describe('URL overrides', () => {
  it('reads control and whep from the query string', () => {
    const overrides = readUrlOverrides({
      search: '?control=http%3A%2F%2F192.168.1.50%3A8080%2F&whep=http%3A%2F%2Fnas%3A8889%2Fperch%2Fwhep',
      hash: ''
    })
    expect(overrides).toEqual({
      controlUrl: 'http://192.168.1.50:8080',
      whepUrl: 'http://nas:8889/perch/whep'
    })
  })

  it('reads them from the hash too', () => {
    expect(readUrlOverrides({ search: '', hash: '#control=http://phone:8080' })).toEqual({
      controlUrl: 'http://phone:8080'
    })
  })

  it('never reads the token, and ignores invalid URLs', () => {
    const overrides = readUrlOverrides({
      search: '?token=leaked&control=javascript:alert(1)&whep=nope',
      hash: '#token=leaked2'
    })
    expect(overrides).toEqual({})
  })

  it('applies URL values as a prefill only, never as the active config', () => {
    const storage = memoryStorage({ [STORAGE_KEY]: JSON.stringify(valid) })

    const loaded = loadConfig(storage, { search: '?control=http://other:9000&token=evil', hash: '' })

    expect(loaded.config).toEqual(valid)
    expect(loaded.prefill).not.toBeNull()
  })

  it('drops the stored token when the override points at a different origin', () => {
    const storage = memoryStorage({ [STORAGE_KEY]: JSON.stringify(valid) })

    const loaded = loadConfig(storage, { search: '?control=http://evil.example', hash: '' })

    expect(loaded.config).toEqual(valid)
    expect(loaded.prefill).toEqual({ ...valid, controlUrl: 'http://evil.example', token: '' })
  })

  it('treats a different port or scheme as a different origin', () => {
    const storage = memoryStorage({ [STORAGE_KEY]: JSON.stringify(valid) })

    expect(loadConfig(storage, { search: '?control=http://192.168.1.50:9090', hash: '' }).prefill?.token).toBe('')
    expect(loadConfig(storage, { search: '?control=https://192.168.1.50:8080', hash: '' }).prefill?.token).toBe('')
  })

  it('keeps the token for a same-origin control URL or a WHEP-only change, but still asks to confirm', () => {
    const storage = memoryStorage({ [STORAGE_KEY]: JSON.stringify(valid) })

    const path = loadConfig(storage, { search: '?control=http://192.168.1.50:8080/other', hash: '' })
    expect(path.prefill).toEqual({ ...valid, controlUrl: 'http://192.168.1.50:8080/other' })

    const whep = loadConfig(storage, { search: '?whep=http://evil.example/whep', hash: '' })
    expect(whep.prefill).toEqual({ ...valid, whepUrl: 'http://evil.example/whep' })
  })

  it('has nothing to confirm when the URL repeats the saved values', () => {
    const storage = memoryStorage({ [STORAGE_KEY]: JSON.stringify(valid) })

    const loaded = loadConfig(storage, { search: '?control=http://192.168.1.50:8080/', hash: '' })

    expect(loaded.prefill).toBeNull()
  })

  it('prefills the first-run dialog when nothing is saved', () => {
    const loaded = loadConfig(memoryStorage(), {
      search: '?control=http://phone:8080&whep=http://nas:8889/perch/whep',
      hash: ''
    })

    expect(loaded.config).toEqual(EMPTY_CONFIG)
    expect(loaded.prefill).toEqual({
      controlUrl: 'http://phone:8080',
      whepUrl: 'http://nas:8889/perch/whep',
      token: ''
    })
  })

  it('never carries an orphaned stored token to a new control URL', () => {
    const storage = memoryStorage({ [STORAGE_KEY]: JSON.stringify({ token: 'orphan' }) })

    const loaded = loadConfig(storage, { search: '?control=http://evil.example', hash: '' })

    expect(loaded.prefill?.token).toBe('')
  })
})

describe('stripUrlOverrides', () => {
  function run(search: string, hash: string) {
    const replaceState = vi.fn()
    stripUrlOverrides({ pathname: '/viewer/', search, hash }, { replaceState })
    return replaceState
  }

  it('removes control, whep and token from the query and the hash, keeping the rest', () => {
    const replaceState = run('?control=http://a&x=1&token=t', '#whep=http://b&y=2')

    expect(replaceState).toHaveBeenCalledWith(null, '', '/viewer/?x=1#y=2')
  })

  it('leaves a clean address bar when only overrides were present', () => {
    expect(run('?control=http://a&whep=http://b', '')).toHaveBeenCalledWith(null, '', '/viewer/')
  })

  it('does not touch the history when there is nothing to strip', () => {
    expect(run('?x=1', '#section')).not.toHaveBeenCalled()
  })

  it('preserves an unrelated hash when only the query changes', () => {
    expect(run('?control=http://a', '#section')).toHaveBeenCalledWith(null, '', '/viewer/#section')
  })
})
