#!/system/bin/sh
set -eu
[ "$(getprop ro.product.device)" = mico_x04g ]
PMIC=/sys/devices/platform/soc/1000f000.pwrap/1000f000.pwrap:mt6392/mt6392-pmic/pmic_access
REGISTERS=/sys/kernel/debug/regmap/1000f000.pwrap/registers
before=$(sed -n 's/^011a: //p' "$REGISTERS")
case "$before" in ''|*[!0-9a-fA-F]*) exit 1 ;; esac
[ "${#before}" = 4 ]
if [ "${1:-}" = --check ]; then
    [ "$((0x$before & 0x40))" = 0 ]
    echo "PTT hardware reset disabled: TOP_RST_MISC=0x$before"
    exit 0
fi
# MT6392 TOP_RST_MISC_CLR: clear only PWRKEY_RST_EN (bit 6).
# The clear alias preserves every other power/reset setting atomically.
printf '011e 0040\n' > "$PMIC"
after=$(sed -n 's/^011a: //p' "$REGISTERS")
case "$after" in ''|*[!0-9a-fA-F]*) exit 1 ;; esac
[ "${#after}" = 4 ]
[ "$((0x$after))" = "$((0x$before & ~0x40))" ]
echo "PTT hardware reset: 0x$before -> 0x$after"
