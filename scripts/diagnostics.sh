#!/usr/bin/env bash
# Read-only device diagnostics. Output may contain network identifiers; keep it local.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$(command -v adb || true)}"
if ! "$ADB" version >/dev/null 2>&1; then
  ADB="$HOME/.local/share/mise/installs/android-sdk/23.0/platform-tools/adb"
fi
"$ADB" version >/dev/null
SERIAL="${1:?Usage: scripts/diagnostics.sh SERIAL}"
DEVICE=$("$ADB" -s "$SERIAL" shell getprop ro.product.device | tr -d '\r')
[[ "$DEVICE" == mico_x04g ]] || { echo "Refusing non-X04G: $DEVICE" >&2; exit 1; }
umask 077
OUT="diagnostics/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT"
read_device() {
  "$ADB" -s "$SERIAL" shell "$2" >"$OUT/$1.txt" 2>&1 || true
}
read_device system 'id; date -u; getprop ro.product.model; getprop ro.product.device; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abilist; getprop ro.build.fingerprint; getprop sys.boot_completed; getprop ro.boot.verifiedbootstate; getenforce; uname -a; cat /proc/cpuinfo; cat /proc/meminfo; cat /proc/uptime; df -h /data; su -c "id; magisk -v; magisk -V"'
read_device display 'wm size; wm density; dumpsys display; dumpsys power; settings get system screen_off_timeout; settings get system screen_brightness; settings get system screen_brightness_mode; settings get global stay_on_while_plugged_in'
read_device input 'ls -l /dev/input; getevent -lp; dumpsys input; cat /proc/bus/input/devices; ls /system/usr/keylayout /vendor/usr/keylayout /odm/usr/keylayout; cat /system/usr/keylayout/mtk*.kl /vendor/usr/keylayout/mtk*.kl /odm/usr/keylayout/mtk*.kl'
read_device audio 'dumpsys audio; dumpsys media.audio_flinger; dumpsys media.audio_policy; cat /proc/asound/cards; cat /proc/asound/pcm'
read_device sensors 'dumpsys sensorservice'
read_device network 'ip -4 addr; ip route; dumpsys connectivity; dumpsys wifi; dumpsys bluetooth_manager'
read_device boot 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME; dumpsys activity activities; pm list packages -d; pm list features; ps -A; ls -l /data/adb/service.d; ls /data/adb/modules; getprop init.svc; ls /system/etc/init /vendor/etc/init'
read_device existing_customization 'cat /data/adb/service.d/lx04-ui-fixes.sh /data/adb/service.d/lx04-hardware-buttons.sh.disabled /data/adb/service.d/disable-rootshell.sh; ls -l /data/adb/modules/lx04_ui_fix; cat /data/adb/modules/lx04_ui_fix/module.prop /data/adb/modules/lx04_ui_fix/service.sh; ls /data/adb/modules/lx04_ui_fix/system/usr/keylayout'
echo "Read-only diagnostics: $OUT"
