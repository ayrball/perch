# 📡 Live Streaming Setup (MediaMTX)

Perch streams the phone's camera to your browser over **WebRTC**, through a small media server called [MediaMTX](https://github.com/bluenviron/mediamtx) running on a computer on your network.

```
 Phone (Perch app) ──WHIP──▶  MediaMTX  ──WHEP──▶  Browser (web viewer)
   publishes video            relays WebRTC          plays video
```

- The phone **publishes** with WHIP (an HTTP POST of a WebRTC offer).
- The browser **plays** with WHEP (the same idea in the other direction).
- Video only for now. Audio is still recorded into the saved segments, just not streamed.
- Everything stays on your network. There is no Perch cloud.

---

## 1. Run MediaMTX

### Docker
```bash
docker run --rm -it \
  -p 8889:8889 \
  -p 8189:8189/udp \
  -e MTX_WEBRTCADDITIONALHOSTS=192.168.1.100 \
  -e MTX_WEBRTCALLOWORIGIN='*' \
  bluenviron/mediamtx
```
Replace `192.168.1.100` with the LAN IP of the computer running MediaMTX.

### Windows binary
1. Download `mediamtx_vX.X.X_windows_amd64.zip` from the [releases page](https://github.com/bluenviron/mediamtx/releases) and extract it.
2. Edit `mediamtx.yml`:
   ```yaml
   webrtcAllowOrigin: '*'            # lets the web viewer (another origin) call MediaMTX
   webrtcAdditionalHosts: [192.168.1.100]   # your computer's LAN IP
   ```
3. Run `mediamtx.exe` and allow it through the Windows firewall (TCP 8889, UDP 8189).

| Port | Protocol | Purpose |
|------|----------|---------|
| `8889` | HTTP | WHIP (phone → server) and WHEP (server → browser) signalling |
| `8189` | UDP | WebRTC media |

## 2. Point the phone at it

In the Perch app: **Settings → Live Streaming**

1. Enter the **Server address (WHIP)**: `http://192.168.1.100:8889/perch/whip` and tap **Apply**.
   `perch` is the stream name; the viewer must use the same one.
2. Switch **Live Streaming** on (or tap **Stream** on the dashboard).
3. The status line should change to **Live**. If the server is down it shows *Server unreachable, retrying…* and reconnects by itself (1 s, 2 s, 4 s … up to 15 s between tries).

The **Publish token** field is optional. It is sent as `Authorization: Bearer <token>`, so only fill it in if you configured MediaMTX authentication that accepts bearer tokens. A default MediaMTX install accepts anyone on the network, so keep it on a network you trust.

## 3. Open the web viewer

```bash
cd web-viewer
npm install
npm run dev
```
Open <http://localhost:3000>. In the settings dialog enter:

- **Live stream (WHEP) URL**: `http://192.168.1.100:8889/perch/whep`
- The phone's control address and token for the remote controls (see [CONTROL_API.md](CONTROL_API.md)).

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| Phone says *Server unreachable* | Wrong IP/port, firewall, or phone and server on different networks/VLANs. Try the WHIP URL in the phone's browser: it should answer (even with an error), not time out. |
| Phone says *Live* but the viewer is black | MediaMTX can't reach the browser for media: set `webrtcAdditionalHosts` to the server's LAN IP and open UDP 8189. |
| Viewer shows a network/CORS error | `webrtcAllowOrigin` is not set to `'*'` (or your viewer's origin) in `mediamtx.yml`. |
| Nothing works from an `https://` page | Browsers block `http://` calls from `https://` pages. Open the viewer over `http://` or `localhost`, or put TLS on MediaMTX and the phone. |
| Works at home, not away | Media is LAN-only (no STUN/TURN is configured). Use a VPN such as Tailscale or WireGuard instead of port-forwarding the camera to the internet. |
| Stream looks soft / drops frames | The phone is hot: Perch lowers the stream to 10 fps and recording to 720p while the battery is above 45 °C. Let it cool or improve ventilation. |

## How it works (for contributors)

`CameraEngine` already analyses every frame for motion and faces, so the stream reuses those 720p frames: `I420Converter` converts and rotates them, `WebRTCPublisher` feeds them to a WebRTC video source and negotiates with the server through `WhipClient`. The publisher runs inside `RecordingService`, so the stream and the 24/7 recording share one camera session.
