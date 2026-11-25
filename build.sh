#!/usr/bin/env bash
# Builds the AirPlay TV receiver APK using the local toolchain.
set -euo pipefail

cd "$(dirname "$0")"

TOOLS="${TOOLS:-../HDRezkaTV/.tools}"

export JAVA_HOME="$TOOLS/jdk/Contents/Home"
export ANDROID_HOME="$TOOLS/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

GRADLE="$TOOLS/gradle-8.11.1/bin/gradle"

if [ ! -x "$GRADLE" ]; then
  echo "Gradle not found at $GRADLE" >&2
  exit 1
fi

case "${1:-debug}" in
  debug)
    "$GRADLE" :app:assembleDebug --no-daemon
    echo
    echo "APK: app/build/outputs/apk/debug/app-debug.apk"
    ;;
  release)
    "$GRADLE" :app:assembleRelease --no-daemon
    echo
    echo "APK: app/build/outputs/apk/release/app-release.apk"
    ;;
  verify)
    "$GRADLE" :core:verify --no-daemon
    ;;
  install)
    "$GRADLE" :app:assembleDebug --no-daemon
    "$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
    ;;
  *)
    echo "usage: $0 [debug|release|verify|install]" >&2
    exit 2
    ;;
esac
