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
