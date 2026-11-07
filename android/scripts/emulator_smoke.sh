#!/usr/bin/env bash
# Emulator smoke test for the Hermes debug APK.
#   - installs the APK, launches it, and runs the on-device Hermes import self-test
#   - if NOUS_API_KEY is set, also sends one live prompt to Nous inference
# Screenshots and logcat go to $OUT (default: ./screens).
# Usage: ANDROID_HOME=... scripts/emulator_smoke.sh [path/to/app-debug.apk]
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
AVD=${AVD:-hermes_x86_64}
OUT=${OUT:-screens}
PKG=com.mycosensai.hermes
mkdir -p "$OUT"

if ! adb devices | awk 'NR>1 && $2=="device"{ok=1} END{exit !ok}'; then
  echo "Starting emulator $AVD..."
  nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -accel on >"$OUT/emulator.log" 2>&1 &
fi
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done

adb install -r "$APK"
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" --ez debug_selftest true
sleep 15
adb exec-out screencap -p >"$OUT/smoke-launch.png"
adb shell pidof "$PKG" >/dev/null || { echo "FAIL: app is not running"; exit 1; }
SELFTEST=$(adb logcat -d -s HermesSelfTest:I | grep -o '{.*}' | tail -1)
echo "SELFTEST $SELFTEST"
echo "$SELFTEST" | grep -q '"ok":true' || { echo "FAIL: Hermes self-test"; exit 1; }

if [ -n "${NOUS_API_KEY:-}" ]; then
  # Debug builds accept a key extra; it is stored encrypted and never logged.
  adb shell am force-stop "$PKG"
  adb shell am start -n "$PKG/.MainActivity" --es nous_api_key "$NOUS_API_KEY" \
    --es debug_prompt "'Reply with the single word: Olympus'"
  sleep 40
  adb exec-out screencap -p >"$OUT/smoke-chat.png"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml "$OUT/ui-chat.xml" >/dev/null
  grep -q 'hermes: ' "$OUT/ui-chat.xml" && echo "PASS: live reply received" || { echo "FAIL: no reply"; exit 1; }
fi
adb logcat -d >"$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then echo "FAIL: crash in logcat"; exit 1; fi
echo "PASS: smoke test"
