import { afterEach, vi } from 'vitest'
import { FakeResizeObserver } from './test-utils/fakeResizeObserver'

// jsdom does not implement media playback; the player only needs the calls to be harmless.
Object.defineProperty(HTMLMediaElement.prototype, 'play', {
  configurable: true,
  value: vi.fn(() => Promise.resolve())
})
Object.defineProperty(HTMLMediaElement.prototype, 'pause', {
  configurable: true,
  value: vi.fn()
})

// Not a stubGlobal on purpose: it must survive vi.unstubAllGlobals() between tests.
Object.defineProperty(globalThis, 'ResizeObserver', {
  configurable: true,
  writable: true,
  value: FakeResizeObserver
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  localStorage.clear()
  window.history.replaceState(null, '', '/')
  FakeResizeObserver.instances = []
})
