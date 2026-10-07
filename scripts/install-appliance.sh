#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$HOME/.local/share/mise/installs/android-sdk/23.0/platform-tools/adb}"
SERIAL="${1:?Usage: scripts/install-appliance.sh SERIAL}"
[[ "$("$ADB" -s "$SERIAL" shell getprop ro.product.device | tr -d '\r')" == mico_x04g ]]
BIN=$(rg --files --hidden --no-ignore android/app/build/intermediates/cxx | rg '/obj/armeabi-v7a/muse-supervisor$' | head -1)
test -x "$BIN"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP_ROOT="/data/local/tmp/muse-x04g-backup/$STAMP"
mkdir -p "backups/$STAMP"
chmod 700 backups "backups/$STAMP"
"$ADB" -s "$SERIAL" shell "umask 077; mkdir -p '$BACKUP_ROOT'; cp -Rp /data/adb/modules/muse_x04g '$BACKUP_ROOT/module'; cp -Rp /data/adb/service.d '$BACKUP_ROOT/service.d'; cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME >'$BACKUP_ROOT/home.txt'"
"$ADB" -s "$SERIAL" pull "$BACKUP_ROOT" "backups/$STAMP"
"$ADB" -s "$SERIAL" push device /data/local/tmp/muse-x04g-update
"$ADB" -s "$SERIAL" push "$BIN" /data/local/tmp/muse-supervisor.new
"$ADB" -s "$SERIAL" shell 'cp -R /data/local/tmp/muse-x04g-update/. /data/adb/modules/muse_x04g/; mkdir -p /data/adb/modules/muse_x04g/bin; mv /data/local/tmp/muse-supervisor.new /data/adb/modules/muse_x04g/bin/muse-supervisor; chown -R 0:0 /data/adb/modules/muse_x04g; chmod 755 /data/adb/modules/muse_x04g/service.sh /data/adb/modules/muse_x04g/bin/muse-supervisor; rm -rf /data/local/tmp/muse-x04g-update'
"$ADB" -s "$SERIAL" shell 'sh /data/adb/modules/muse_x04g/disable-ptt-reset.sh && sync'
"$ADB" -s "$SERIAL" shell 'for p in $(pidof muse-supervisor); do case "$(readlink /proc/$p/exe)" in /data/adb/modules/muse_x04g/bin/muse-supervisor*) kill "$p" ;; esac; done; sleep 1; nohup /data/adb/modules/muse_x04g/bin/muse-supervisor /data/adb/modules/muse_x04g >/data/adb/modules/muse_x04g/supervisor.log 2>&1 </dev/null &'
echo "Appliance supervisor installed. Backup: $BACKUP_ROOT"
