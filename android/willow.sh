#!/usr/bin/env bash
# Builds Willow with Gradle and drives it on the USB phone from the PC (Git Bash).
#   willow.sh install          debug build (test phone: logs + adb test hooks), install it
#   willow.sh release          release build (Grandpa's phone: no logs, no test hooks), install it
#   willow.sh log              stream Willow's log (Ctrl+C to stop)
#   willow.sh ocr              log what the WeChat screen reads as right now
#   willow.sh ai [text]        ask the AI about [text] (or the current video) to check the key works
#   willow.sh test [intercept|tip|allow] [card]   force a decision on the current video
#   willow.sh selftest         Kotlin rules vs shared/classifier.js
#   willow.sh unittest         FeedParser tests on the PC
# The AI key is read at build time from prototype-web/.env (DASHSCOPE_API_KEY, Qwen).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
export JAVA_HOME="${JAVA_HOME:-/e/Android_Studio/jbr}"
# The Honor Magic5 Pro needs adb's libusb backend; Git Bash must not rewrite /sdcard paths.
export ADB_LIBUSB=1 MSYS_NO_PATHCONV=1
send() { "$ADB" shell am broadcast -p com.willow -a "com.willow.$1" "${@:2}" > /dev/null; }

case "${1:-}" in
  install)
    ./gradlew -q :app:assembleDebug
    echo "installing… (MagicOS asks to confirm on the phone)"
    "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk ;;
  release)
    ./gradlew -q :app:assembleRelease
    echo "installing… (MagicOS asks to confirm on the phone)"
    "$ADB" install -r app/build/outputs/apk/release/app-release.apk ;;
  log)      "$ADB" logcat -v time -s Willow ;;
  ocr)      send OCR; sleep 3; "$ADB" logcat -d -v time -s Willow | grep -A80 " OCR " | tail -80 ;;
  ai|qwen)
    if [ -n "${2:-}" ]; then send AI --es text "$2"; else send AI; fi
    sleep 10; "$ADB" logcat -d -v time -s Willow | grep "AI test" | tail -3 ;;
  test)     send TEST --es level "${2:-intercept}" --es card "${3:-bp_stop}" ;;
  selftest) send SELFTEST; sleep 2; "$ADB" logcat -d -s Willow | grep SELFTEST | tail -30 ;;
  unittest) ./gradlew -q :app:testDebugUnitTest && echo "FeedParser tests passed" ;;
  *) sed -n '2,11p' "$0" ;;
esac
