#!/bin/sh
# Run as root on the server after inspecting its existing Caddy setup.
# Usage: sudo sh install-site.sh /tmp/foodbridge-site.tar.gz food.kukakur.ru.caddy
set -eu
archive=$(realpath "$1")
snippet=$(realpath "$2")
base=/opt/foodbridge
conf=/etc/caddy/sites-enabled/food.kukakur.ru.caddy
stamp=$(date -u +%Y%m%dT%H%M%SZ)
release="$base/releases/$stamp"
test -d /etc/caddy/sites-enabled
grep -q 'import /etc/caddy/sites-enabled/\*.caddy' /etc/caddy/Caddyfile
install -d -m 755 "$release" "$base/backups"
tar -xzf "$archive" -C "$release"
test -s "$release/index.html"
test -s "$release/.well-known/assetlinks.json"
test -s "$release/downloads/FoodBridge.apk"
old_link=$(readlink "$base/site" || true)
had_config=false
if test -f "$conf"; then cp -p "$conf" "$base/backups/$stamp.caddy"; had_config=true; fi
rollback() {
    if $had_config; then cp -p "$base/backups/$stamp.caddy" "$conf"; else rm -f "$conf"; fi
    if test -n "$old_link"; then ln -sfn "$old_link" "$base/site"; fi
}
trap rollback EXIT
install -m 644 "$snippet" "$conf"
ln -sfn "$release" "$base/site"
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
systemctl reload caddy
trap - EXIT
systemctl is-active caddy
echo "Deployed FoodBridge at $release. Existing site configuration was not edited."
