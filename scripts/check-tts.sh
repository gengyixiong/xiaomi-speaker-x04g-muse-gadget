#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
adb_path="${ADB:-$HOME/.local/share/mise/installs/android-sdk/23.0/platform-tools/adb}"
serial="${1:-21065C0VR35518}"
[[ -s .secrets/gemini-api-key ]] || { printf 'Configure .secrets/gemini-api-key first; see docs/maintenance.md.\n' >&2; exit 1; }
scripts/build.sh :app:assembleDebug :app:assembleDebugAndroidTest
"$adb_path" -s "$serial" install -r android/app/build/outputs/apk/debug/app-debug.apk
"$adb_path" -s "$serial" install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result="$("$adb_path" -s "$serial" shell am instrument -w io.muse.x04g.test/io.muse.x04g.TtsCheck)"
printf '%s\n' "$result"
"$adb_path" -s "$serial" uninstall io.muse.x04g.test
"$adb_path" -s "$serial" shell sync
"$adb_path" -s "$serial" shell am start -n io.muse.x04g/.MainActivity >/dev/null
[[ "$result" == *'PASS: Leda PCM playback'* ]]
