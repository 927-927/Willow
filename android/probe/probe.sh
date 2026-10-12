#!/usr/bin/env bash
# Drives the probe on the USB phone from the PC (Git Bash).
#   probe.sh install   build + install the probe
#   probe.sh log       stream the probe's log (Ctrl+C to stop)
#   probe.sh dump      dump the current WeChat screen
#   probe.sh shot      accessibility screenshot, pulled to android/probe/build/shots/
#   probe.sh mute | unmute
#   probe.sh clean     delete the probe's screenshots from the phone
set -euo pipefail
cd "$(dirname "$0")"
ADB="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb"
# The Honor Magic5 Pro's ADB interface uses Windows' generic WinUSB driver, which adb's default
# Windows backend can't see. The libusb backend can.
export ADB_LIBUSB=1
# Stop Git Bash from rewriting phone paths like /sdcard/... into C:/Program Files/Git/sdcard/...
export MSYS_NO_PATHCONV=1
PKG=com.willow.probe
send() { "$ADB" shell am broadcast -p $PKG -a "$PKG.$1" > /dev/null; }

case "${1:-}" in
  install) ../build-offline.sh probe --install ;;
  log)     "$ADB" logcat -v time -s WillowProbe ;;
  dump)    send DUMP ;;
  mute)    send MUTE ;;
  unmute)  send UNMUTE ;;
  shot)
    send SHOT; sleep 2
    mkdir -p build/shots
    "$ADB" pull "/sdcard/Android/data/$PKG/files/" build/shots/ > /dev/null && ls build/shots/files ;;
  clean)   "$ADB" shell rm -f "/sdcard/Android/data/$PKG/files/*.png" ;;
  *) sed -n '2,9p' "$0" ;;
esac
