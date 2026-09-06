#!/usr/bin/env bash
# Boots an Android TV API 28 emulator (the same API level as Fire OS 7) and
# installs YourTOBA on it. Useful for checking a change without the TV.
#
#   ./emulator.sh          boot + install + launch
#   ./emulator.sh logs     ...then tail the log
#
# One-time setup, if the AVD does not exist yet:
#   sdkmanager "emulator" "system-images;android-28;android-tv;x86"
#   avdmanager create avd -n yourtoba_tv -k "system-images;android-28;android-tv;x86" -d tv_1080p
set -euo pipefail
cd "$(dirname "$0")"
. ./env.sh
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"

PORT=5556
DEV="emulator-$PORT"

if ! adb devices | grep -q "$DEV"; then
  echo "==> Booting yourtoba_tv"
  "$ANDROID_HOME/emulator/emulator" -avd yourtoba_tv -no-window -no-audio \
    -no-boot-anim -gpu swiftshader_indirect -no-snapshot -port $PORT \
    >/tmp/yourtoba-emu.log 2>&1 &
  adb -s "$DEV" wait-for-device
  until [ "$(adb -s "$DEV" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 3
  done
fi

echo "==> Building and installing"
./gradlew --quiet assembleRelease
adb -s "$DEV" install -r app/build/outputs/apk/release/app-release.apk
adb -s "$DEV" logcat -c
adb -s "$DEV" shell am start -n com.yourtoba.tv/.MainActivity >/dev/null

echo "==> Running. Screenshot: adb -s $DEV shell screencap -p /sdcard/s.png"
[ "${1:-}" = "logs" ] && adb -s "$DEV" logcat YourTOBA:V chromium:E '*:S'
exit 0
