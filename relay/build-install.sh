#!/usr/bin/env bash
# Regenerates relay/install.sh with the current server.js, package.json and
# web/index.html embedded, so the installer is one self-contained file.
set -euo pipefail
cd "$(dirname "$0")"

embed() { # embed <dest-path> <src-file>
  printf "cat > %s <<'__RD_EOF__'\n" "$1"
  cat "$2"
  printf "__RD_EOF__\n\n"
}

{
  sed -n '1,/^# __EMBED_FILES__$/p' install.template.sh
  embed '"$APP_DIR/server.js"' server.js
  embed '"$APP_DIR/package.json"' package.json
  embed '"$APP_DIR/public/index.html"' ../web/index.html
  sed -n '/^# __EMBED_FILES__$/,$p' install.template.sh | tail -n +2
} > install.sh
chmod +x install.sh
echo "wrote relay/install.sh"
