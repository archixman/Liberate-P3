#!/usr/bin/env bash
#
# Build the RMX5070 Root APK.
#
#   ./build.sh                 # debug APK -> app/build/outputs/apk/debug/
#   ./build.sh assembleRelease # release APK (needs keystore.properties)
#
# Prereqs: JDK 17+ (JAVA_HOME), Android SDK with platform 37 + build-tools
# 37.0.0 (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties sdk.dir).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

tasks=("$@")
if [ ${#tasks[@]} -eq 0 ]; then
    tasks=(:app:assembleDebug)
fi

cd "$here"
./gradlew "${tasks[@]}" --no-daemon --console=plain

if [ "${tasks[0]}" = ":app:assembleDebug" ]; then
    echo "[+] APK: $here/app/build/outputs/apk/debug/app-debug.apk"
else
    echo "[+] done (see $here/app/build/outputs/apk/)"
fi
