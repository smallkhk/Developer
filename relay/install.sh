#!/usr/bin/env bash
# RemoteDroid relay — one-shot installer for an Ubuntu/Debian VPS.
#
#   sudo bash install.sh            # auto-detects your public IP
#   sudo bash install.sh 203.0.113.45
#
# Installs Node.js 20, nginx and a free Let's Encrypt cert for <IP>.nip.io,
# runs the relay as a systemd service, and serves the browser controller at
# https://<IP>.nip.io/. Safe to re-run (it updates the code and keeps your
# relay secret).
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then echo "Run as root: sudo bash $0"; exit 1; fi
if ! command -v apt-get >/dev/null; then echo "This script supports Ubuntu/Debian only."; exit 1; fi

IP="${1:-$(curl -4 -fsS https://api.ipify.org || curl -4 -fsS https://ifconfig.me)}"
if ! [[ "$IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Could not detect a public IPv4. Pass it explicitly: sudo bash $0 <your-ip>"; exit 1
fi
HOSTNAME_FQDN="${IP}.nip.io"
APP_DIR=/opt/remotedroid-relay
SECRET_FILE=/etc/remotedroid-relay.env
echo "==> Using host: $HOSTNAME_FQDN"

echo "==> Installing packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y curl ca-certificates nginx certbot python3-certbot-nginx
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 18 ]; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi

echo "==> Writing relay code to $APP_DIR"
id relay >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin relay
mkdir -p "$APP_DIR/public"

# __EMBED_FILES__
cat > "$APP_DIR/server.js" <<'__RD_EOF__'
'use strict';

/**
 * RemoteDroid relay.
 *
 * A dumb, safe forwarder: the phone and the browser both open a WebSocket to
 * the same room (the URL path, e.g. wss://host/my-phone). The relay pairs the
 * two peers in that room and forwards every message from one to the other,
 * unchanged — text frames (JSON control) and binary frames (H.264 video) alike.
 *
 * It never reads your access token; that is checked peer-to-peer between the
 * app and the browser. The relay only moves bytes.
 *
 * Config via env vars:
 *   PORT            listen port (default 8080)
 *   HOST            bind address (default 0.0.0.0)
 *   RELAY_SECRET    optional. If set, every client must connect with
 *                   ?k=<secret> or it is rejected. A cheap gate so random
 *                   scanners can't even open a room. This is NOT your app
 *                   token — keep that separate.
 *   MAX_ROOMS       max simultaneous rooms (default 50)
 *   MAX_MSG_BYTES   max single message size (default 8 MiB)
 */

const fs = require('fs');
const path = require('path');
const http = require('http');
const { URL } = require('url');
const { WebSocketServer } = require('ws');

const PORT = parseInt(process.env.PORT || '8080', 10);
const HOST = process.env.HOST || '0.0.0.0';
const RELAY_SECRET = process.env.RELAY_SECRET || '';
const MAX_ROOMS = parseInt(process.env.MAX_ROOMS || '50', 10);
const MAX_MSG_BYTES = parseInt(process.env.MAX_MSG_BYTES || String(8 * 1024 * 1024), 10);
const CONTROLLER_HTML = process.env.CONTROLLER_HTML || path.join(__dirname, 'public', 'index.html');

// roomName -> Set<ws>
const rooms = new Map();

const server = http.createServer((req, res) => {
  // A trivial health endpoint so you can curl the VPS to check it's alive.
  if (req.url === '/healthz') {
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end(`ok rooms=${rooms.size}\n`);
    return;
  }
  // Serve the browser controller at "/" so you can open https://<host>/ directly.
  if (req.method === 'GET' && (req.url === '/' || req.url === '/index.html')) {
    fs.readFile(CONTROLLER_HTML, (err, html) => {
      if (err) { res.writeHead(404); res.end('controller page not installed\n'); return; }
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
      res.end(html);
    });
    return;
  }
  res.writeHead(426, { 'content-type': 'text/plain' });
  res.end('Upgrade required\n');
});

const wss = new WebSocketServer({
  server,
  maxPayload: MAX_MSG_BYTES,
  perMessageDeflate: false, // video is already compressed; deflate just adds latency
});

function roomNameFrom(reqUrl) {
  const u = new URL(reqUrl, 'http://x');
  // Everything after the leading slash is the room name; "/" is not valid.
  const name = decodeURIComponent(u.pathname.replace(/^\/+/, '')).trim();
  return name;
}

wss.on('connection', (ws, req) => {
  const u = new URL(req.url, 'http://x');

  if (RELAY_SECRET && u.searchParams.get('k') !== RELAY_SECRET) {
    ws.close(4001, 'relay secret required');
    return;
  }

  const room = roomNameFrom(req.url);
  if (!room) {
    ws.close(4002, 'room name required in path');
    return;
  }

  let peers = rooms.get(room);
  if (!peers) {
    if (rooms.size >= MAX_ROOMS) {
      ws.close(4003, 'relay full');
      return;
    }
    peers = new Set();
    rooms.set(room, peers);
  }

  // Two peers per room: a device and a controller. If a third shows up, boot
  // the oldest so a reconnecting phone/browser can always take its slot back.
  if (peers.size >= 2) {
    const oldest = peers.values().next().value;
    try { oldest.close(4004, 'replaced by new peer'); } catch (_) {}
    peers.delete(oldest);
  }
  peers.add(ws);
  ws._room = room;
  ws.isAlive = true;

  log(`join   room=${room} peers=${peers.size}`);

  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (data, isBinary) => {
    const set = rooms.get(room);
    if (!set) return;
    for (const peer of set) {
      if (peer !== ws && peer.readyState === peer.OPEN) {
        peer.send(data, { binary: isBinary });
      }
    }
  });

  const leave = () => {
    const set = rooms.get(room);
    if (!set) return;
    set.delete(ws);
    if (set.size === 0) rooms.delete(room);
    log(`leave  room=${room} peers=${set.size}`);
  };
  ws.on('close', leave);
  ws.on('error', leave);
});

// Heartbeat: drop sockets that stopped answering (dead phone, dropped Wi-Fi).
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { ws.terminate(); continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch (_) {}
  }
}, 30_000);

wss.on('close', () => clearInterval(heartbeat));

function log(msg) {
  console.log(`${new Date().toISOString()} ${msg}`);
}

server.listen(PORT, HOST, () => {
  log(`relay listening on ${HOST}:${PORT}  secret=${RELAY_SECRET ? 'on' : 'off'}`);
});

// Clean shutdown so systemd restarts are graceful.
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    log(`${sig} received, closing`);
    for (const ws of wss.clients) { try { ws.close(1001, 'server shutdown'); } catch (_) {} }
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 2000).unref();
  });
}
__RD_EOF__

cat > "$APP_DIR/package.json" <<'__RD_EOF__'
{
  "name": "remotedroid-relay",
  "version": "1.0.0",
  "description": "WebSocket relay that pairs a RemoteDroid phone and browser in a room.",
  "main": "server.js",
  "type": "commonjs",
  "scripts": {
    "start": "node server.js"
  },
  "engines": {
    "node": ">=18"
  },
  "dependencies": {
    "ws": "^8.18.0"
  }
}
__RD_EOF__

cat > "$APP_DIR/public/index.html" <<'__RD_EOF__'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>RemoteDroid Controller</title>
<meta name="theme-color" content="#0b0e13" />
<style>
  :root {
    color-scheme: dark;
    --bg: #0b0e13; --panel: #151a22; --panel2: #1d2430; --line: #2a3340;
    --fg: #eef1f6; --muted: #8b95a6; --accent: #3b82f6; --accent2: #2563eb;
    --ok: #34d399; --bad: #f87171;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body { height: 100%; }
  body {
    margin: 0; background: var(--bg); color: var(--fg);
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    display: flex; flex-direction: column; overflow: hidden;
  }

  /* Top bar */
  .bar {
    display: flex; align-items: center; gap: 10px;
    padding: 10px 14px; background: var(--panel);
    border-bottom: 1px solid var(--line);
  }
  .brand { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 15px; }
  .dot { width: 9px; height: 9px; border-radius: 50%; background: var(--bad); transition: background .3s; }
  .dot.on { background: var(--ok); }
  .meta { display: flex; gap: 10px; align-items: center; margin-left: auto;
          font-size: 12px; color: var(--muted); }
  #stats { color: var(--accent); }

  /* Buttons & inputs */
  button, select, .filelabel {
    background: var(--panel2); color: var(--fg); border: 1px solid var(--line);
    padding: 9px 12px; border-radius: 10px; font-size: 14px; cursor: pointer;
    display: inline-flex; align-items: center; gap: 6px; white-space: nowrap;
    transition: background .15s, border-color .15s;
  }
  button:hover, select:hover, .filelabel:hover { background: #262f3d; }
  button:active { transform: scale(.97); }
  button:disabled { opacity: .4; cursor: default; }
  button.primary { background: var(--accent); border-color: var(--accent); font-weight: 600; }
  button.primary:hover { background: var(--accent2); }
  button.icon { padding: 9px 11px; }
  input[type=text], input[type=password] {
    background: var(--bg); border: 1px solid var(--line); color: var(--fg);
    padding: 10px 12px; border-radius: 10px; font-size: 14px; width: 100%;
  }

  /* Settings drawer (relay url / token) — hidden by default */
  .settings {
    display: grid; gap: 8px; padding: 12px 14px;
    background: var(--panel); border-bottom: 1px solid var(--line);
  }
  .settings.hidden { display: none; }
  .settings label { font-size: 12px; color: var(--muted); display: grid; gap: 4px; }

  /* Screen area */
  main { flex: 1; display: flex; align-items: center; justify-content: center;
         overflow: hidden; padding: 8px; min-height: 0; }
  #stage { max-width: 100%; max-height: 100%; display: flex; }
  #screen, #rtcVideo {
    max-width: 100%; max-height: 100%; background: #000; border-radius: 12px;
    touch-action: none; cursor: crosshair; display: block;
    box-shadow: 0 10px 50px rgba(0,0,0,.55);
  }

  /* Bottom controls */
  footer { background: var(--panel); border-top: 1px solid var(--line); padding: 8px; }
  .nav { display: flex; gap: 8px; justify-content: center; }
  .nav button { flex: 1 1 0; justify-content: center; font-size: 18px; max-width: 120px; }
  .tools {
    display: flex; gap: 8px; align-items: center; margin-top: 8px;
    overflow-x: auto; padding-bottom: 2px; scrollbar-width: thin;
  }
  .tools::-webkit-scrollbar { height: 6px; }
  .tools::-webkit-scrollbar-thumb { background: var(--line); border-radius: 3px; }
  #textbox { flex: 1 0 180px; }
  .filelabel { position: relative; overflow: hidden; }
  .filelabel input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }

  @media (max-width: 640px) {
    .bar { padding: 9px 11px; gap: 8px; }
    .brand span.name { display: none; }         /* keep just the dot on tiny screens */
    .meta { gap: 7px; }
    button, select, .filelabel, #textbox { font-size: 15px; padding: 11px 12px; }
    .nav button { font-size: 20px; }
  }
</style>
</head>
<body>
  <header class="bar">
    <div class="brand"><span class="dot" id="dot"></span><span class="name">RemoteDroid</span></div>
    <div class="meta">
      <span id="status">idle</span>
      <span id="stats"></span>
      <span id="batt"></span>
    </div>
    <button id="connect" class="primary">Connect</button>
    <button id="gear" class="icon" title="relay settings">⚙</button>
  </header>

  <div id="settings" class="settings hidden">
    <label>Relay URL
      <input id="url" type="text" placeholder="wss://your-relay/room?k=secret" />
    </label>
    <label>Access token
      <input id="token" type="password" placeholder="access token" />
    </label>
  </div>

  <main>
    <div id="stage">
      <canvas id="screen" width="2" height="2"></canvas>
      <video id="rtcVideo" autoplay playsinline muted style="display:none"></video>
    </div>
  </main>

  <footer>
    <div class="nav">
      <button data-key="back" title="Back">◁</button>
      <button data-key="home" title="Home">◯</button>
      <button data-key="recents" title="Recents">▢</button>
    </div>
    <div class="tools">
      <input id="textbox" type="text" placeholder="type text, Enter to send" />
      <select id="quality" title="stream quality">
        <option value="auto" selected>Auto</option>
        <option value="low">Low</option>
        <option value="medium">Medium</option>
        <option value="high">High</option>
      </select>
      <select id="appList" title="launch an app"><option value="">Apps…</option></select>
      <button id="zoomIn" title="zoom in">🔍+</button>
      <button id="zoomOut" title="zoom out">🔍−</button>
      <button data-key="volup" title="volume up">🔊</button>
      <button data-key="voldown" title="volume down">🔉</button>
      <button data-key="notifications" title="notifications">🔔</button>
      <button data-key="lock" title="lock">🔒</button>
      <button data-key="power" title="power menu">⏻</button>
      <button id="clipBtn" title="send text to phone clipboard">📋</button>
      <label class="filelabel" title="send a file to the phone">📎<input id="fileInput" type="file" /></label>
      <button id="recBtn" title="record to a file">● Rec</button>
      <button id="fsBtn" title="fullscreen">⛶</button>
      <button id="pause" disabled title="pause stream">⏸</button>
      <button id="resume" disabled title="resume stream">▶</button>
    </div>
  </footer>

<script type="module">
const $ = s => document.querySelector(s);
const url = $('#url'), token = $('#token'), statusEl = $('#status');
const canvas = $('#screen'), ctx = canvas.getContext('2d');
const video = $('#rtcVideo'), stage = $('#stage');
let activeEl = canvas;        // the element currently showing the phone
let pc = null;                // RTCPeerConnection in Fast (WebRTC) mode
let rtcRetry = null;          // debounce for reconnection requests

// Remember last-used relay + token locally (per-browser convenience only).
try {
  url.value = localStorage.getItem('rd_url') || '';
  token.value = localStorage.getItem('rd_token') || '';
} catch (e) {}
// Sensible defaults so you don't have to type anything.
if (!url.value && location.protocol === 'https:')
  url.value = `wss://${location.host}/my-phone`;
if (!token.value) token.value = '1234';

// Settings gear reveals the relay URL / token.
$('#gear').addEventListener('click', () => $('#settings').classList.toggle('hidden'));

function setDot(on) { $('#dot').classList.toggle('on', on); }

let ws = null, decoder = null, configBytes = null;
let deviceW = 0, deviceH = 0, haveKey = false;
let rxBytes = 0, rxFrames = 0;

// Once a second, show throughput + fps so slowdowns are visible.
setInterval(() => {
  const statsEl = $('#stats');
  if (!ws || ws.readyState !== 1) { statsEl.textContent = ''; return; }
  const kbps = Math.round(rxBytes * 8 / 1000);
  statsEl.textContent = `${rxFrames} fps · ${kbps} kbps`;
  rxBytes = 0; rxFrames = 0;
}, 1000);

function setStatus(t) { statusEl.textContent = t; }

function connect() {
  if (ws) { ws.close(); ws = null; }
  try {
    localStorage.setItem('rd_url', url.value);
    localStorage.setItem('rd_token', token.value);
  } catch (e) {}

  ws = new WebSocket(url.value);
  ws.binaryType = 'arraybuffer';
  setStatus('connecting…');

  ws.onopen = () => {
    setStatus('authenticating…');
    ws.send(JSON.stringify({ t: 'auth', token: token.value }));
  };

  ws.onmessage = ev => {
    if (typeof ev.data === 'string') return onControl(JSON.parse(ev.data));
    onVideo(new Uint8Array(ev.data));
  };

  ws.onclose = () => {
    setStatus('disconnected'); teardownDecoder(); teardownPc();
    setButtons(false); setDot(false); $('#connect').textContent = 'Connect';
  };
  ws.onerror = () => setStatus('connection error');
}

function onControl(msg) {
  switch (msg.t) {
    case 'auth_ok':
      setStatus('connected'); setButtons(true); setDot(true);
      $('#connect').textContent = 'Disconnect';
      ws.send(JSON.stringify({ t: 'keyframe' }));
      ws.send(JSON.stringify({ t: 'applist' }));
      break;
    case 'auth_fail':
      setStatus('wrong token'); ws.close(); break;
    case 'meta':
      deviceW = msg.w; deviceH = msg.h;
      setMode(msg.mode === 'webrtc' ? 'webrtc' : 'ws');
      break;
    case 'rtc_offer':
      handleOffer(msg.sdp); break;
    case 'rtc_ice':
      if (pc && msg.candidate) pc.addIceCandidate(
        new RTCIceCandidate({ candidate: msg.candidate, sdpMid: msg.sdpMid, sdpMLineIndex: msg.sdpMLineIndex })
      ).catch(() => {});
      break;
    case 'state':
      setStatus(msg.paused ? 'paused' : 'streaming'); break;
    case 'file_done':
      setStatus(`file saved on phone: ${msg.name} (Downloads)`); break;
    case 'file_error':
      setStatus('file transfer failed'); break;
    case 'autoq':
      // Phone changed quality on its own; reflect it without firing change.
      $('#quality').value = 'auto';
      setStatus(`auto quality: ${msg.level}`); break;
    case 'status':
      $('#batt').textContent = `🔋 ${msg.battery}%${msg.charging ? ' ⚡' : ''}`; break;
    case 'applist': {
      const sel = $('#appList');
      sel.innerHTML = '<option value="">Apps…</option>';
      for (const a of msg.apps) {
        const o = document.createElement('option');
        o.value = a.pkg; o.textContent = a.name;
        sel.appendChild(o);
      }
      setStatus(`${msg.apps.length} apps`); break;
    }
  }
}

// Switch between WebSocket video (canvas) and WebRTC (video element).
function setMode(mode) {
  if (mode === 'webrtc') {
    canvas.style.display = 'none'; video.style.display = 'block'; activeEl = video;
    teardownDecoder();
  } else {
    video.style.display = 'none'; canvas.style.display = 'block'; activeEl = canvas;
    teardownPc();
  }
}

async function handleOffer(sdp) {
  teardownPc();
  pc = new RTCPeerConnection({
    iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
  });
  pc.ontrack = e => { video.srcObject = e.streams[0]; };
  pc.onicecandidate = e => {
    if (e.candidate) send({
      t: 'rtc_ice', candidate: e.candidate.candidate,
      sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex
    });
  };
  pc.onconnectionstatechange = () => {
    setStatus(`webrtc: ${pc.connectionState}`);
    // If the path drops, ask the phone to renegotiate (it reuses capture).
    if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
      clearTimeout(rtcRetry);
      rtcRetry = setTimeout(() => { if (ws && ws.readyState === 1) send({ t: 'rtc_start' }); }, 1500);
    }
  };
  await pc.setRemoteDescription({ type: 'offer', sdp });
  const answer = await pc.createAnswer();
  await pc.setLocalDescription(answer);
  send({ t: 'rtc_answer', sdp: answer.sdp });
}

function teardownPc() {
  if (pc) { try { pc.close(); } catch (e) {} pc = null; }
  if (video.srcObject) { video.srcObject = null; }
}

function setupDecoder() {
  teardownDecoder();
  decoder = new VideoDecoder({
    output: frame => {
      if (canvas.width !== frame.displayWidth) {
        canvas.width = frame.displayWidth;
        canvas.height = frame.displayHeight;
      }
      ctx.drawImage(frame, 0, 0);
      frame.close();
      rxFrames++;
    },
    error: e => setStatus('decode error: ' + e.message)
  });
  // No description => the decoder reads Annex-B (what MediaCodec emits).
  decoder.configure({ codec: 'avc1.42E01E', optimizeForLatency: true });
  haveKey = false;
}

function teardownDecoder() {
  if (decoder && decoder.state !== 'closed') { try { decoder.close(); } catch (e) {} }
  decoder = null;
}

function onVideo(buf) {
  rxBytes += buf.length;
  const type = buf[0];           // 0 config, 1 keyframe, 2 delta
  const payload = buf.subarray(1);
  if (type === 0) { configBytes = payload; return; }
  if (!decoder) setupDecoder();

  let data = payload, chunkType = 'delta';
  if (type === 1) {
    haveKey = true; chunkType = 'key';
    if (configBytes) {               // prepend SPS/PPS so the IDR is decodable
      data = new Uint8Array(configBytes.length + payload.length);
      data.set(configBytes, 0);
      data.set(payload, configBytes.length);
    }
  }
  if (!haveKey) return;              // wait for the first keyframe
  try {
    decoder.decode(new EncodedVideoChunk({
      type: chunkType, timestamp: performance.now() * 1000, data
    }));
  } catch (e) { setStatus('decode error'); }
}

// --- sending input --------------------------------------------------------
function send(obj) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); }

// Map a canvas pixel position to a 0..1 coordinate on the device.
function norm(ev) {
  const r = activeEl.getBoundingClientRect();
  return {
    x: Math.min(1, Math.max(0, (ev.clientX - r.left) / r.width)),
    y: Math.min(1, Math.max(0, (ev.clientY - r.top) / r.height))
  };
}

let down = null, downT = 0;
stage.addEventListener('pointerdown', ev => { down = norm(ev); downT = Date.now(); });
stage.addEventListener('pointerup', ev => {
  if (!down) return;
  const up = norm(ev), dt = Date.now() - downT;
  const dist = Math.hypot(up.x - down.x, up.y - down.y);
  if (dist < 0.02 && dt < 400) send({ t: 'tap', x: up.x, y: up.y });
  else if (dist < 0.02) send({ t: 'longpress', x: up.x, y: up.y });
  else send({ t: 'swipe', x1: down.x, y1: down.y, x2: up.x, y2: up.y, ms: Math.min(dt, 1200) });
  down = null;
});

document.querySelectorAll('[data-key]').forEach(b =>
  b.addEventListener('click', () => send({ t: 'key', k: b.dataset.key })));

// Launch an app from the dropdown.
$('#appList').addEventListener('change', e => {
  if (e.target.value) { send({ t: 'launch', pkg: e.target.value }); e.target.value = ''; }
});

// Zoom buttons -> pinch at the center of the screen.
$('#zoomIn').addEventListener('click', () => send({ t: 'pinch', x: 0.5, y: 0.5, out: true }));
$('#zoomOut').addEventListener('click', () => send({ t: 'pinch', x: 0.5, y: 0.5, out: false }));

// Mouse wheel over the screen -> short swipe (scroll).
stage.addEventListener('wheel', e => {
  e.preventDefault();
  const p = norm(e);
  const dy = e.deltaY > 0 ? -0.18 : 0.18; // wheel down scrolls content up
  send({ t: 'swipe', x1: p.x, y1: p.y, x2: p.x, y2: Math.min(1, Math.max(0, p.y + dy)), ms: 120 });
}, { passive: false });

$('#textbox').addEventListener('keydown', ev => {
  if (ev.key === 'Enter') { send({ t: 'text', s: ev.target.value }); ev.target.value = ''; }
});

$('#connect').addEventListener('click', () => {
  if (ws && ws.readyState === 1) { ws.close(); ws = null; }
  else connect();
});

// Auto-connect on load when we already have a relay URL + token.
if (url.value && token.value && 'VideoDecoder' in window) {
  setTimeout(connect, 300);
}
$('#pause').addEventListener('click', () => send({ t: 'pause' }));
$('#resume').addEventListener('click', () => send({ t: 'resume' }));
$('#quality').addEventListener('change', e => send({ t: 'quality', level: e.target.value }));
$('#clipBtn').addEventListener('click', () => {
  send({ t: 'clipboard', s: $('#textbox').value });
  setStatus('clipboard sent to phone');
});

// File upload: browser -> phone, chunked, with simple backpressure.
$('#fileInput').addEventListener('change', async e => {
  const file = e.target.files[0];
  if (!file || !ws || ws.readyState !== 1) return;
  const CHUNK = 64 * 1024;
  send({ t: 'file_begin', name: file.name, size: file.size });
  let sent = 0;
  for (let off = 0; off < file.size; off += CHUNK) {
    // Wait if the send buffer is getting full, so we don't blow up memory.
    while (ws.bufferedAmount > 4 * 1024 * 1024) await new Promise(r => setTimeout(r, 20));
    const buf = await file.slice(off, off + CHUNK).arrayBuffer();
    ws.send(buf);
    sent += buf.byteLength;
    setStatus(`uploading ${file.name}: ${Math.round(sent / file.size * 100)}%`);
  }
  send({ t: 'file_end' });
  e.target.value = '';
});

// Drag-and-drop a file onto the screen to upload it.
["dragover","drop"].forEach(ev => stage.addEventListener(ev, e => e.preventDefault()));
stage.addEventListener('drop', e => {
  const f = e.dataTransfer?.files?.[0];
  if (f) { $('#fileInput').files = e.dataTransfer.files; $('#fileInput').dispatchEvent(new Event('change')); }
});

// Record the live view to a .webm file.
let recorder = null, recChunks = [];
$('#recBtn').addEventListener('click', () => {
  if (recorder) { recorder.stop(); return; }
  const stream = canvas.captureStream(30);
  recChunks = [];
  recorder = new MediaRecorder(stream, { mimeType: 'video/webm' });
  recorder.ondataavailable = e => { if (e.data.size) recChunks.push(e.data); };
  recorder.onstop = () => {
    const blob = new Blob(recChunks, { type: 'video/webm' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `remotedroid-${Date.now()}.webm`;
    a.click();
    recorder = null; $('#recBtn').textContent = '● Rec'; $('#recBtn').style.color = '';
  };
  recorder.start();
  $('#recBtn').textContent = '■ Stop'; $('#recBtn').style.color = '#ff5c5c';
});

$('#fsBtn').addEventListener('click', () => {
  if (document.fullscreenElement) document.exitFullscreen();
  else document.querySelector('main').requestFullscreen?.();
});

// Keyboard: Escape -> Back (when not typing in a field).
document.addEventListener('keydown', e => {
  if (e.target.tagName === 'INPUT') return;
  if (e.key === 'Escape') send({ t: 'key', k: 'back' });
});

function setButtons(on) {
  $('#pause').disabled = !on;
  $('#resume').disabled = !on;
}

if (!('VideoDecoder' in window))
  setStatus('This browser lacks WebCodecs — use a recent Chrome/Edge.');
</script>
</body>
</html>
__RD_EOF__


cd "$APP_DIR"
npm install --omit=dev --no-audit --no-fund
chown -R relay:relay "$APP_DIR"

# Keep an existing secret on re-runs; otherwise generate one.
if [ ! -f "$SECRET_FILE" ]; then
  echo "RELAY_SECRET=$(openssl rand -hex 24)" > "$SECRET_FILE"
  chmod 600 "$SECRET_FILE"
fi
# shellcheck disable=SC1090
. "$SECRET_FILE"

echo "==> Installing systemd service"
cat > /etc/systemd/system/remotedroid-relay.service <<UNIT
[Unit]
Description=RemoteDroid WebSocket relay
After=network.target

[Service]
Type=simple
User=relay
WorkingDirectory=$APP_DIR
ExecStart=/usr/bin/node $APP_DIR/server.js
Restart=always
RestartSec=3
Environment=PORT=8080
Environment=HOST=127.0.0.1
EnvironmentFile=$SECRET_FILE
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable remotedroid-relay
systemctl restart remotedroid-relay

echo "==> Configuring nginx for $HOSTNAME_FQDN"
cat > /etc/nginx/sites-available/remotedroid-relay <<NGINX
server {
    listen 80;
    server_name $HOSTNAME_FQDN;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }
}
NGINX
ln -sf /etc/nginx/sites-available/remotedroid-relay /etc/nginx/sites-enabled/remotedroid-relay
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx

echo "==> Opening firewall (80, 443, ssh)"
if command -v ufw >/dev/null; then
  ufw allow OpenSSH >/dev/null || true
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
  ufw --force enable >/dev/null
fi

echo "==> Getting HTTPS certificate"
if ! certbot --nginx -d "$HOSTNAME_FQDN" --non-interactive --agree-tos \
     --register-unsafely-without-email --redirect; then
  echo
  echo "!! Certificate request failed. Usually port 80 is blocked by your VPS"
  echo "!! provider's firewall/security group. Open TCP 80 and 443 there, then"
  echo "!! re-run: sudo bash $0 $IP"
  exit 1
fi
systemctl reload nginx

sleep 1
if ! curl -fsS http://127.0.0.1:8080/healthz >/dev/null; then
  HEALTH="FAILED — relay not running (check: journalctl -u remotedroid-relay)"
elif curl -fsS -m 15 "https://$HOSTNAME_FQDN/healthz" >/dev/null; then
  HEALTH=OK
else
  HEALTH="relay OK, but port 443 is unreachable — open TCP 443 in your VPS provider's firewall / security group"
fi

cat <<DONE

============================================================
 RemoteDroid relay is installed.   Health check: $HEALTH

 Browser controller (open this on your computer):
   https://$HOSTNAME_FQDN/

 Relay URL — put the SAME one in the phone app and the browser:
   wss://$HOSTNAME_FQDN/my-phone?k=$RELAY_SECRET

 Also pick an access token and type the same one on both sides.
 ("my-phone" is the room name; any name works if both sides match.)

 Logs:     journalctl -u remotedroid-relay -f
 Restart:  systemctl restart remotedroid-relay
 Secret:   $SECRET_FILE
============================================================
DONE
