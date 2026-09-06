#!/usr/bin/env bash
# Build and sideload YourTOBA to a Fire TV over network ADB.
#
#   ./install.sh 192.168.1.42        # build, connect, install
#   ./install.sh 192.168.1.42 logs   # ...then tail YourTOBA's logcat
#
# On the Fire TV first: Settings > My Fire TV > Developer Options >
# enable "ADB debugging". If Developer Options is missing, go to
# Settings > My Fire TV > About, highlight the device name, press select
# seven times. The IP is under Settings > My Fire TV > About > Network.
#
# The stick will show an "Allow USB debugging?" prompt on first connect.
# Accept it on the TV, then re-run.
set -euo pipefail

cd "$(dirname "$0")"
# shellcheck source=env.sh
. ./env.sh

IP="${1:-}"
if [[ -z "$IP" ]]; then
  echo "usage: ./install.sh <fire-tv-ip> [logs]" >&2
  exit 2
fi
[[ "$IP" == *:* ]] || IP="$IP:5555"

APK="app/build/outputs/apk/release/app-release.apk"

echo "==> Building"
./gradlew --quiet assembleRelease

echo "==> Verifying APK"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify "$APK" >/dev/null
echo "    signature ok: $(du -h "$APK" | cut -f1)"

echo "==> Connecting to $IP"
adb disconnect "$IP" >/dev/null 2>&1 || true
adb connect "$IP"

echo "==> Installing"
# -r reinstalls keeping data; -d allows a downgrade during development.
adb -s "$IP" install -r -d "$APK"

echo "==> Launching"
adb -s "$IP" shell monkey -p com.yourtoba.tv -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 \
  || adb -s "$IP" shell am start -n com.yourtoba.tv/.MainActivity

echo "==> Done. YourTOBA is on the Fire TV home row."

if [[ "${2:-}" == "logs" ]]; then
  echo "==> Tailing logcat (ctrl-c to stop)"
  adb -s "$IP" logcat -c
  adb -s "$IP" logcat YourTOBA:V chromium:E AndroidRuntime:E '*:S'
fi
