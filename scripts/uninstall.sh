#!/usr/bin/env bash
# Removes pairing with the APK; keeps the disabled module and backups for recovery.
set -euo pipefail
ADB="${ADB:-$HOME/.local/share/mise/installs/android-sdk/23.0/platform-tools/adb}"
SERIAL="${1:?Usage: scripts/uninstall.sh SERIAL}"
[[ "$("$ADB" -s "$SERIAL" shell getprop ro.product.device | tr -d '\r')" == mico_x04g ]]
"$ADB" -s "$SERIAL" shell 'touch /data/adb/modules/muse_x04g/maintenance /data/adb/modules/muse_x04g/disable; for p in $(pidof muse-supervisor); do case "$(readlink /proc/$p/exe)" in /data/adb/modules/muse_x04g/bin/muse-supervisor*) kill "$p" ;; esac; done; am stopservice -n io.muse.x04g/.MuseService; am force-stop io.muse.x04g; am start -a android.intent.action.MAIN -c android.intent.category.HOME'
"$ADB" -s "$SERIAL" uninstall io.muse.x04g
echo 'APK removed. Reboot Android to restore the original middle-button mapping.'
