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
