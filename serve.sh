#!/usr/bin/env bash
# Serves the APK over your LAN so the Fire TV's Downloader app can fetch it.
# Use this when ADB is not an option.
#
#   ./serve.sh
#
# Then on the Fire TV, open Downloader and type the URL it prints.
# Ctrl-C here when the install is done.
set -euo pipefail
cd "$(dirname "$0")"

APK="dist/YourTOBA-0.1.0.apk"
if [[ ! -f "$APK" ]]; then
  echo "No APK yet. Building." >&2
  . ./env.sh
./tools/make-keystore.sh
  ./gradlew --quiet assembleTvRelease
  mkdir -p dist
  cp app/build/outputs/apk/tv/release/app-tv-release.apk "$APK"
fi

PORT="${1:-8000}"
IP=$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1); exit}')
[[ -n "${IP:-}" ]] || IP=$(hostname -I 2>/dev/null | awk '{print $1}')

echo
echo "  APK   : $APK  ($(du -h "$APK" | cut -f1))"
echo "  SHA256: $(sha256sum "$APK" | cut -d' ' -f1)"
echo
echo "  On the Fire TV, open Downloader and enter:"
echo
echo "      http://$IP:$PORT/YourTOBA-0.1.0.apk"
echo
echo "  Ctrl-C to stop serving."
echo
exec python3 -m http.server "$PORT" --bind 0.0.0.0 --directory dist
