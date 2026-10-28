# RemoteDroid relay

A tiny WebSocket relay for your VPS. The phone and the browser both connect to
the same room (the URL path) and the relay forwards everything between them. It
never sees your app token — it just moves bytes.

## Why you need TLS (wss://)

The Android app sends over `wss://` and browsers block `ws://` from an HTTPS
page. So in production you terminate TLS at a reverse proxy (nginx/Caddy) on
443 and proxy to the Node process on localhost:8080. Plain `ws://` is fine only
for a quick LAN test.

## No domain? Use your IP via nip.io (recommended)

You do **not** need to buy a domain. `nip.io` is a free wildcard DNS: the
hostname `YOUR_IP.nip.io` automatically resolves to `YOUR_IP`. Let's Encrypt
will happily issue a real certificate for it, so you get proper `wss://` with
no domain purchase and no app changes.

Example: if your VPS is `203.0.113.45`, your relay URL becomes
`wss://203.0.113.45.nip.io/<room-name>`.

Follow the **Production install** below exactly, but everywhere it says
`relay.example.com`, use `YOUR_IP.nip.io` instead. That's the only change.

> If `nip.io` ever misbehaves, `sslip.io` works the same way:
> `YOUR_IP.sslip.io`.

### Opening the browser controller (secure-context note)

The controller page uses WebCodecs, which browsers only enable in a "secure
context". Two easy ways to get one:

- **Serve it from localhost on your own computer** (simplest):
  ```bash
  cd web
  python3 -m http.server 9000
  # open http://localhost:9000  — WebCodecs works, and it can reach wss://
  ```
  `http://localhost` counts as secure, and a page there may connect to
  `wss://YOUR_IP.nip.io/...` with no mixed-content problem.

- **Or host it on the VPS over HTTPS** and open `https://YOUR_IP.nip.io/app`
  (add an nginx `location /app { root ...; }` serving `web/index.html`).

The phone app needs no such trick — just set its relay URL to
`wss://YOUR_IP.nip.io/<room>`.

## Plain ws:// fallback (no TLS, trusted networks only)

If you just want to test fast and don't care about encryption, you can skip TLS
entirely and use `ws://YOUR_IP:8080/<room>`. Two catches:

1. Open the browser controller from `http://localhost:9000` (not `https`), or
   the browser blocks the insecure socket.
2. Android blocks cleartext sockets by default, so the app needs
   `android:usesCleartextTraffic="true"` added to its `<application>` tag (one
   line) and a rebuild. Tell me if you want this — I'll wire a config that
   allows cleartext only to your specific IP, which is safer than enabling it
   globally.

Your traffic (including the app token) is unencrypted in this mode, so only use
it on a network you trust. The nip.io path above is strongly preferred.

---

## Quick start (bare, for a LAN test)

```bash
cd relay
npm install
PORT=8080 HOST=0.0.0.0 node server.js
# phone + browser → ws://<vps-ip>:8080/<any-room-name>
curl http://<vps-ip>:8080/healthz   # -> ok rooms=0
```

## Production install (Ubuntu/Debian VPS, TLS, systemd)

Assumes a domain like `relay.example.com` pointing at the VPS.

### 1. Install Node + nginx

```bash
sudo apt update
sudo apt install -y nginx
# Node 20 LTS:
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
```

### 2. Deploy the relay

```bash
sudo useradd --system --home /opt/remotedroid-relay --shell /usr/sbin/nologin relay
sudo mkdir -p /opt/remotedroid-relay
sudo cp server.js package.json /opt/remotedroid-relay/
cd /opt/remotedroid-relay
sudo npm install --omit=dev
sudo chown -R relay:relay /opt/remotedroid-relay
```

### 3. systemd service

```bash
sudo cp remotedroid-relay.service /etc/systemd/system/
# Optional but recommended: set a relay secret
sudo systemctl edit remotedroid-relay   # add: [Service]\nEnvironment=RELAY_SECRET=<long-random>
sudo systemctl daemon-reload
sudo systemctl enable --now remotedroid-relay
sudo systemctl status remotedroid-relay
```

Generate a good secret: `openssl rand -hex 24`.

### 4. nginx + TLS

```bash
sudo tee /etc/nginx/sites-available/relay <<'NGINX'
server {
    listen 80;
    server_name relay.example.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 3600s;   # keep long-lived sockets open
        proxy_send_timeout 3600s;
    }
}
NGINX
sudo ln -sf /etc/nginx/sites-available/relay /etc/nginx/sites-enabled/relay
sudo nginx -t && sudo systemctl reload nginx

# Free HTTPS cert (adds the 443 server block automatically):
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d relay.example.com --redirect
```

### 5. Firewall

```bash
sudo ufw allow 80,443/tcp
# Do NOT open 8080 — nginx reaches it on localhost. The relay binds 127.0.0.1.
```

### 6. Connect

- **Phone app** and **browser** → `wss://relay.example.com/<room-name>`
- If you set `RELAY_SECRET`, append it: `wss://relay.example.com/<room-name>?k=<secret>`
- Pick any room name (e.g. your phone's nickname). Both sides must use the same one.

## Two layers of protection

1. **Relay secret** (`RELAY_SECRET`, optional) — gates who may open a room at
   all, so random scanners can't. Travels in the URL.
2. **App token** (in the phone app + browser) — the real control gate, checked
   peer-to-peer; the relay never sees it. Always set this.

Use a long, unguessable room name too — it's effectively part of the address.

## Operating

```bash
sudo systemctl restart remotedroid-relay
journalctl -u remotedroid-relay -f        # live logs
curl https://relay.example.com/healthz    # ok rooms=N
```

## Config (env vars)

| var | default | meaning |
|---|---|---|
| `PORT` | 8080 | listen port |
| `HOST` | 0.0.0.0 | bind address (use 127.0.0.1 behind nginx) |
| `RELAY_SECRET` | *(off)* | require `?k=<secret>` on connect |
| `MAX_ROOMS` | 50 | max simultaneous rooms |
| `MAX_MSG_BYTES` | 8388608 | max single message size |
