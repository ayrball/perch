# 🧪 Testing

Three layers: fast JVM unit tests, tests that run on a real phone, and a web viewer suite. Then a manual pass on hardware for the things no test can see (camera behaviour, thermals, the lock screen).

## Running everything

```bash
# Android unit tests (JVM, no device needed)
cd android && ./gradlew :app:testDebugUnitTest

# Android on-device tests (needs a connected device or emulator)
cd android && ./gradlew :app:connectedDebugAndroidTest

# Web viewer
cd web-viewer && npm test && npm run build
```

Gradle needs JDK 17–21 (Kotlin 2.0 cannot parse the version string of JDK 25).
Note: `connectedDebugAndroidTest` **uninstalls the app from the device afterwards** (Android Gradle Plugin default), including its data.

## What each suite covers

### Android unit tests — 67
| Area | Examples |
|---|---|
| Control API | Auth (missing / wrong / malformed token), every endpoint, input validation, clamping, error codes |
| Control server (real sockets) | CORS preflight, 401/429 lockout, oversized and chunked bodies, garbage input, slow clients cut off, concurrent clients |
| Retention | Which recordings are deleted, in what order, never an in-flight upload |
| Migrations | v1 → v2 SQL, never drops data, rejects downgrades |
| Streaming | WHIP client against a fake server, frame-rate limiter averages, restart backoff |
| Misc | Thermal hysteresis, JSON reader/writer, byte formatting, token generation, motion detector |

### Android on-device tests — 28
These need Android's real SQLite, real files and the native WebRTC libraries:
- **Migration** — the real v1 → v2 SQL on SQLite, including rollback of a failed step and "migrated schema equals fresh install".
- **Retention manager** — real files in a scratch directory: delete-after-upload only with Drive connected, oldest-first eviction, never an uploading file, stale orphan cleanup that spares the segment being written, history pruning.
- **I420 converter** — pixel-exact checks of rotation (90/180/270), chroma planes, row padding, interleaved chroma and odd sizes on the real libyuv.
- **WebRTC publisher** — against a scripted WHIP server: a well-formed send-only video offer with ICE candidates and the bearer token, retry with backoff, cleanup (DELETE) of a half-open session, unreachable server, malformed URL, and 8 rapid start/stop cycles without leaking threads.

### Web viewer — 172
API client (auth header, timeouts including a stalled body, 401 and 429 handling), WHEP signalling and reconnect races (unmount mid-negotiation, StrictMode, transient disconnects), config and URL-override security (a saved token is never sent to a different origin), the Settings dialog (focus trap, Escape), remote-control behaviour, letterbox geometry for portrait video, formatting edge cases, and an error boundary for malformed phone responses.

## Manual pass on real hardware

Device: **Google Pixel 10a, Android 16** (debug build, USB connected). Control API reached through `adb forward`; the stream pointed at a small fake WHIP server through `adb reverse`.

| Scenario | Result |
|---|---|
| Install over an existing v1 database | Migrated to v2, data kept, new column and indexes present |
| 1-minute segment rollover | Segments saved and indexed with no gaps |
| Camera flip, quality change | In-progress segment finalized cleanly, new segment on the new camera/quality |
| Flashlight via API; on the front camera | Works; front camera correctly refused (HTTP 409) |
| Fake 46 °C battery (`dumpsys battery set temp`) | Throttled and re-bound at 720p; still throttled at 43 °C; recovered below 41 °C |
| Another app (Google Camera) takes the camera for 10–35 s | Recording pauses, resumes within ~5 s of release, no unnecessary re-binds |
| Stop while the app is open | Service fully stops, API closes, wake locks released, last segment saved; dashboard offers a one-tap restart |
| Microphone permission revoked | Starts as a camera-only foreground service and records silently, no crash |
| 480 requests, 8 parallel clients + a stalled client | All answered, memory and thread count flat |
| WebRTC offer from the real service | Reached the server with the bearer token, send-only video, 6 LAN candidates; retries at 1, 2, 4, 8, 15, 15 s |
| Encoder bitrate | ~45 MB/min at 1080p (the unconfigured default was 90 MB/min) |

### Bugs this process found and fixed
Recordings at twice the needed size; a recorder that reported "recording" while receiving no video after the camera was preempted; Stop leaving a zombie service while the app was bound; a foreground-service crash on Android 14+ without the microphone; errored segments being discarded and a possible tight restart loop; uploads that cancelled and duplicated themselves; a control server that one slow client could stall; a native-memory race on shutdown; and several web-viewer issues (token sent to arbitrary `?control=` hosts, requests that could stall forever).

## Not covered yet
- The live video path **end to end** against a real MediaMTX server.
- The Google sign-in and upload round trip (needs a Google Cloud OAuth client and account).
- Behaviour across a real reboot, and long soak runs (hours/days).
- Playback of recorded files.

## Demo data for screenshots
The web viewer screenshots in the README use a throwaway mock of the phone API with fake values. To try the viewer without a phone, serve the same JSON shape (see [CONTROL_API.md](CONTROL_API.md)) and enter its address and any token in the viewer's settings dialog.
