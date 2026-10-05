#!/usr/bin/env bash
set -euo pipefail
ADB="${ADB:-$HOME/.local/share/mise/installs/android-sdk/23.0/platform-tools/adb}"
exec "$ADB" -s "${1:?Usage: scripts/logs.sh SERIAL}" logcat -v time -s MuseX04G
