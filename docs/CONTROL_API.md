# 🎛️ Phone Control API

The web viewer's status, events and remote controls talk to a small HTTP API served **by the phone itself**. It is **off by default**.

## Turning it on

Perch app → **Settings → Remote Control** → switch **Remote Control API** on.
The screen then shows:

- **Address**: `http://<phone-ip>:8080`
- **Access token**: a random 32-character secret (tap *Show token* / *Copy*). *New token* signs every viewer out.

Enter both in the web viewer's settings dialog.

### Security model
- Every request except CORS preflights needs `Authorization: Bearer <token>`. The token is compared in constant time.
- After 5 wrong tokens from one address in a minute that address gets `429` until the minute is up.
- It is plain HTTP on your local network. Anyone on that network who has the token can see camera status and events and trigger the flashlight, siren, camera flip and video quality. They **cannot** download recordings or the live video through this API.
- Do not port-forward it to the internet. Use a VPN (Tailscale/WireGuard) to reach it from outside.
- The token is stored in the app's private settings and is excluded from Android backups.

## Endpoints

All bodies and responses are JSON. Every response carries `Access-Control-Allow-Origin: *` (including `401`/`4xx`), and `OPTIONS` preflights are answered without credentials.

### `GET /api/status`
```json
{
  "deviceName": "Pixel 6a",
  "timestamp": 1730000000000,
  "recording": true,
  "recordingSeconds": 252,
  "segmentMinutes": 5,
  "streaming": true,
  "streamState": "LIVE",
  "motion": false,
  "faceCount": 1,
  "faces": [{ "x": 0.38, "y": 0.25, "w": 0.22, "h": 0.32 }],
  "flashlight": false,
  "camera": "back",
  "videoQuality": "1080p",
  "throttled": false,
  "sirenActive": false,
  "battery": { "percent": 92, "charging": true, "temperatureC": 33.4 },
  "storage": { "usedBytes": 123456789, "limitBytes": 8589934592, "freeBytes": 45678901234 },
  "backup": {
    "state": "connected",
    "account": "you@gmail.com",
    "autoUpload": true,
    "intervalMinutes": 15,
    "pending": 2,
    "uploaded": 40,
    "quotaUsedBytes": 4200000000,
    "quotaTotalBytes": 16106127360
  }
}
```
- `faces`: top-left corner and size as fractions (0–1) of the upright video frame.
- `streamState`: `IDLE`, `CONNECTING`, `LIVE` or `RECONNECTING`.
- `throttled`: the phone is hot and running at reduced quality.
- `backup.state`: `disconnected`, `connected` or `needs_auth` (Drive sign-in expired).
- `quotaUsedBytes` / `quotaTotalBytes` are `null` until the first successful backup check; `quotaTotalBytes` is also `null` for unlimited accounts.

### `GET /api/events?limit=50`
Newest first, `limit` 1–200 (default 50).
```json
[{ "id": 7, "type": "MOTION", "title": "Motion Detected", "description": "…",
   "timestamp": 1730000000000, "faceCount": 0, "confidence": 0.8 }]
```
`type` is `MOTION`, `FACE_STRANGER`, `FACE_RECOGNIZED`, `TAMPER` or `PET`.

### Actions
Each returns the updated status object.

| Request | Body | Notes |
|---------|------|-------|
| `POST /api/flashlight` | `{"on": true}` (omit to toggle) | `409` if the active camera has no flash (e.g. front camera) |
| `POST /api/siren` | `{"seconds": 10}` | 1–60 s, default 10. Plays the alarm sound at full alarm volume |
| `POST /api/siren/stop` | – | Also available as *Silence siren* in the phone's notification |
| `POST /api/camera/flip` | – | Takes effect at the next recording segment boundary (the current segment is saved first) |
| `POST /api/quality` | `{"quality": "720p"\|"1080p"\|"4K"}` | Same: applies from the next segment |

### Errors
Non-2xx responses look like `{"error": "message"}`: `400` bad input, `401` bad/missing token, `404`, `405`, `409`, `413` body over 4 KB, `429` too many failed attempts.
