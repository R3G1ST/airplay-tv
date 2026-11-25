#!/usr/bin/env bash
# Runs the protocol core as a desktop AirPlay receiver on this machine.
# It shows up in the AirPlay menu of every iPhone/Mac on the LAN and logs the
# whole handshake — handy for protocol debugging without a TV.
#
# macOS itself listens on 7000 (AirPlay Receiver), so the demo uses 7100.
set -euo pipefail
cd "$(dirname "$0")"
TOOLS="${TOOLS:-../HDRezkaTV/.tools}"
export JAVA_HOME="$TOOLS/jdk/Contents/Home"
export ANDROID_HOME="$TOOLS/sdk"
export AIRPLAY_PORT="${AIRPLAY_PORT:-7100}"
exec "$TOOLS/gradle-8.11.1/bin/gradle" :core:demo --no-daemon -q
