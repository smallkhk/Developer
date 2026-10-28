# RemoteDroid

Control your own Android phone from your browser: live screen view plus
tap / swipe / type / hardware-key control, over a relay you run.

```
  Android app  ──(WebSocket)──▶  your relay  ◀──(WebSocket)──  browser page
   screen capture + input                                     view + control
```

- **Phone** (`app/`): captures the screen (H.264), performs your remote input,
  runs as a foreground service with a visible notification.
- **Browser** (`web/index.html`): decodes the video with WebCodecs and sends
  your clicks/keys back.
- **Relay** (yours): a WebSocket server that just **forwards messages between
  the two sides of a room**. It never needs your token.

## v1 features

- Live screen streaming (H.264 / WebCodecs).
- Tap, long-press, swipe.
- Text into the focused field.
- Hardware keys: Back, Home, Recents, Notifications.
- **Pause / Resume from the browser** with no on-phone tap (see below).
- Token auth: the browser must send your secret before anything streams.

## The screen-capture prompt (important)

Android requires you to approve screen capture **once, on the phone**, each
time a capture *session* starts. You cannot approve it remotely.

- **Pause** (browser) stops the stream but keeps the capture session alive →
  **Resume** needs no phone tap. Use this day to day.
- **Stop** (notification or app) ends the session fully → restarting needs one
  tap on the phone again. A reboot also requires re-approval.

Some apps (banking, DRM video) force a black screen during capture — that is an
Android rule, not a bug here.

## Build & install the app

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone: enter your relay URL + token → tap **Enable remote input** and
turn RemoteDroid on in Accessibility → tap **Start remote session** and accept
the capture prompt. Open `web/index.html` in Chrome/Edge, enter the same URL +
token, click **Connect**.

## Relay contract

Both sides open a WebSocket to the same room URL. The relay forwards every
message from one peer to the other, unchanged — **text frames** (JSON) and
**binary frames** (video) alike. That is all it must do.

### Browser → phone (JSON text)

| message | meaning |
|---|---|
| `{"t":"auth","token":"…"}` | authenticate (first message) |
| `{"t":"tap","x":0.5,"y":0.3}` | tap at normalized 0..1 coords |
| `{"t":"longpress","x":…,"y":…}` | long press |
| `{"t":"swipe","x1":…,"y1":…,"x2":…,"y2":…,"ms":250}` | swipe |
| `{"t":"key","k":"back\|home\|recents\|notifications"}` | hardware key |
| `{"t":"text","s":"hello"}` | set text of focused field |
| `{"t":"pause"}` / `{"t":"resume"}` | pause/resume stream |
| `{"t":"keyframe"}` | request a fresh keyframe |

### Phone → browser

- JSON text: `{"t":"auth_ok"}`, `{"t":"auth_fail"}`,
  `{"t":"meta","w":1080,"h":2340}`, `{"t":"state","capturing":true,"paused":false}`.
- Binary video: each frame is `[1 header byte][H.264 Annex-B bytes]`.
  Header `0` = SPS/PPS config, `1` = keyframe, `2` = delta frame.

### A minimal relay (Node.js, for reference)

Any two-peer-per-room forwarder works. Example with `ws`:

```js
const { WebSocketServer } = require('ws');
const wss = new WebSocketServer({ port: 8080 });
const rooms = new Map(); // path -> Set<socket>

wss.on('connection', (sock, req) => {
  const room = req.url;
  let peers = rooms.get(room); if (!peers) rooms.set(room, peers = new Set());
  peers.add(sock);
  sock.on('message', (data, isBinary) => {
    for (const p of peers) if (p !== sock && p.readyState === 1) p.send(data, { binary: isBinary });
  });
  sock.on('close', () => peers.delete(sock));
});
```

Point both the app and the browser at `ws://host:8080/<any-room-name>`.

## Not in v1 (easy follow-ups)

File transfer, audio streaming, phone→browser clipboard auto-read (OS-restricted),
resolution/bitrate controls in the UI.
