#!/usr/bin/env bash
# Build and sideload YourTOBA to a Fire TV over network ADB.
#
#   ./install.sh 192.168.1.42             # build + install the TV build
#   ./install.sh 192.168.1.42 logs        # ...then tail YourTOBA's logcat
#   FLAVOR=phone ./install.sh <ip>        # install YourTOBA Mobile instead
#
# For a phone on USB you usually want plain adb rather than an IP:
#   FLAVOR=phone ./install.sh usb
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
./tools/make-keystore.sh

IP="${1:-}"
if [[ -z "$IP" ]]; then
  echo "usage: ./install.sh <fire-tv-ip> [logs]" >&2
  exit 2
fi
if [[ "$IP" == "usb" ]]; then
  USB=1
else
  [[ "$IP" == *:* ]] || IP="$IP:5555"
fi

FLAVOR="${FLAVOR:-tv}"
case "$FLAVOR" in
  tv|phone) ;;
  *) echo "FLAVOR must be tv or phone" >&2; exit 2 ;;
esac
CAP="$(tr '[:lower:]' '[:upper:]' <<< "${FLAVOR:0:1}")${FLAVOR:1}"
APK="app/build/outputs/apk/$FLAVOR/release/app-$FLAVOR-release.apk"
PKG=$([ "$FLAVOR" = tv ] && echo com.yourtoba.tv || echo com.yourtoba.mobile)

echo "==> Building"
./gradlew --quiet "assemble${CAP}Release"

echo "==> Verifying APK"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify "$APK" >/dev/null
echo "    signature ok: $(du -h "$APK" | cut -f1)"

if [[ -z "${USB:-}" ]]; then
  echo "==> Connecting to $IP"
  adb disconnect "$IP" >/dev/null 2>&1 || true
  adb connect "$IP"
  TARGET=(-s "$IP")
else
  echo "==> Using the USB-attached device"
  TARGET=()
fi

echo "==> Installing"
# -r reinstalls keeping data; -d allows a downgrade during development.
adb "${TARGET[@]}" install -r -d "$APK"

echo "==> Launching"
adb "${TARGET[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
  || adb "${TARGET[@]}" shell am start -n "$PKG/com.yourtoba.MainActivity"

echo "==> Done. Installed $PKG"

if [[ "${2:-}" == "logs" ]]; then
  echo "==> Tailing logcat (ctrl-c to stop)"
  adb "${TARGET[@]}" logcat -c
  adb "${TARGET[@]}" logcat YourTOBA:V chromium:E AndroidRuntime:E '*:S'
fi
