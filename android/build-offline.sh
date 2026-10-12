#!/usr/bin/env bash
# Builds an app module's APK without Gradle, using only what Android Studio and the Android SDK
# already contain (bundled JDK + Kotlin compiler, aapt2, d8, zipalign, apksigner). No downloads.
#   android/build-offline.sh app             → android/app/build/app.apk
#   android/build-offline.sh probe --install → also installs it on the USB phone
# The `app` module also packs shared/rules and shared/debunks as assets, so it reads the same
# JSON as the web prototype.
set -euo pipefail
MODULE="${1:?usage: build-offline.sh <module> [--install]}"
cd "$(dirname "$0")/$MODULE"

STUDIO="${STUDIO:-/e/Android_Studio}"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
KOTLINC="$STUDIO/plugins/Kotlin/kotlinc"
export JAVA_HOME="$STUDIO/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
KEYSTORE="$HOME/.android/debug.keystore"
APK="build/$MODULE.apk"

rm -rf build && mkdir -p build/classes build/dex build/assets

echo "1/5 resources"
if [ "$MODULE" = "app" ]; then
  cp ../../shared/rules/*.json ../../shared/debunks/debunks.json ../../shared/samples/feed.json build/assets/
fi
[ -d src/main/assets ] && cp -r src/main/assets/. build/assets/
"$BT/aapt2" compile --dir src/main/res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$PLATFORM" \
  --manifest src/main/AndroidManifest.xml -A build/assets \
  --min-sdk-version 26 --target-sdk-version 35 --version-code 1 --version-name 0.1 \
  build/res.zip

echo "2/5 kotlin"
"$KOTLINC/bin/kotlinc" src/main/kotlin -classpath "$PLATFORM" -jvm-target 11 -no-reflect \
  -d build/classes 2>&1 | grep -v "^warning: unable to find kotlin-stdlib.jar" || true
[ -n "$(find build/classes -name '*.class' | head -1)" ] || { echo "kotlin compile failed"; exit 1; }

echo "3/5 dex"
# This d8 is older than the bundled Kotlin, so it prints thousands of harmless "Kotlin metadata"
# warnings. They go to build/d8.log; it is only shown if dexing actually fails.
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$PLATFORM" \
  --output build/dex $(find build/classes -name '*.class') "$KOTLINC/lib/kotlin-stdlib.jar" \
  > build/d8.log 2>&1 || { grep -E "^Error" build/d8.log; echo "dex failed (see build/d8.log)"; exit 1; }

echo "4/5 package"
python -I -c "import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', zipfile.ZIP_DEFLATED) as z: z.write(sys.argv[2], 'classes.dex')" \
  build/unsigned.apk build/dex/classes.dex
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

echo "5/5 sign"
if [ ! -f "$KEYSTORE" ]; then
  # The standard Android debug keystore (same one Android Studio creates), so later Gradle builds can upgrade it.
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
java -jar "$BT/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$APK" build/aligned.apk 2>&1 | grep -v "^WARNING" || true

echo "built: android/$MODULE/$APK ($(du -h "$APK" | cut -f1))"

if [ "${2:-}" = "--install" ]; then
  # The Honor test phone needs adb's libusb backend (see android/probe/FINDINGS.md).
  ADB_LIBUSB=1 "$SDK/platform-tools/adb" install -r "$APK"
fi
