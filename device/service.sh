#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/disable-ptt-reset.sh" >>"$MODDIR/pmic-reset.log" 2>&1 || log -t MuseX04G "PTT hardware reset disable failed"
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
# Let the existing LX04 UI normalization script finish first. The same
# supervisor also recovers the UI if a late Home launch covers it.
sleep 30
rm -f /data/user/0/io.muse.x04g/files/maintenance
exec "$MODDIR/bin/muse-supervisor" "$MODDIR" >>"$MODDIR/supervisor.log" 2>&1
