#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$HOME/.local/share/mise/installs/android-sdk/23.0/platform-tools/adb}"
SERIAL="${1:?Usage: scripts/install-input.sh SERIAL}"
[[ "$("$ADB" -s "$SERIAL" shell getprop ro.product.device | tr -d '\r')" == mico_x04g ]]
"$ADB" -s "$SERIAL" shell 'test ! -d /data/adb/modules/muse_x04g'
mkdir -p backups/phase2-input
chmod 700 backups backups/phase2-input
"$ADB" -s "$SERIAL" pull /system/usr/keylayout/mtk-pmic-keys.kl backups/phase2-input/mtk-pmic-keys.kl
"$ADB" -s "$SERIAL" shell 'umask 077; mkdir -p /data/local/tmp/muse-x04g-backup/phase2-input; test -f /data/local/tmp/muse-x04g-backup/phase2-input/mtk-pmic-keys.kl || cp -p /system/usr/keylayout/mtk-pmic-keys.kl /data/local/tmp/muse-x04g-backup/phase2-input/mtk-pmic-keys.kl'
python3 - <<'PY'
from pathlib import Path
p=Path('backups/phase2-input/mtk-pmic-keys.kl')
assert p.read_text().split()==['key','114','VOLUME_DOWN','key','248','MUTE'], 'Unexpected keylayout; leave device unchanged'
PY
"$ADB" -s "$SERIAL" push device /data/local/tmp/muse-x04g-module
"$ADB" -s "$SERIAL" shell 'cp -R /data/local/tmp/muse-x04g-module /data/adb/modules/muse_x04g; chown -R 0:0 /data/adb/modules/muse_x04g; find /data/adb/modules/muse_x04g -type d -exec chmod 755 {} \;; find /data/adb/modules/muse_x04g -type f -exec chmod 644 {} \;; rm -rf /data/local/tmp/muse-x04g-module'
echo 'Key mapping installed; reboot activates it. Rollback: touch /data/adb/modules/muse_x04g/disable, then reboot.'
