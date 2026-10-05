#!/usr/bin/env bash
# Update the APK and its private token. System integration is installed separately.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$HOME/.local/share/mise/installs/android-sdk/23.0/platform-tools/adb}"
SERIAL="${1:?Usage: scripts/install.sh SERIAL}"
[[ "$("$ADB" -s "$SERIAL" shell getprop ro.product.device | tr -d '\r')" == mico_x04g ]]
test -s .secrets/sdk-token
"$ADB" -s "$SERIAL" install -r android/app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$SERIAL" shell pm grant io.muse.x04g android.permission.ACCESS_FINE_LOCATION
"$ADB" -s "$SERIAL" shell pm grant io.muse.x04g android.permission.RECORD_AUDIO
"$ADB" -s "$SERIAL" shell 'run-as io.muse.x04g sh -c "umask 077; mkdir -p files; cat >files/sdk-token"' <.secrets/sdk-token
"$ADB" -s "$SERIAL" shell sync
"$ADB" -s "$SERIAL" shell am start -n io.muse.x04g/.MainActivity
