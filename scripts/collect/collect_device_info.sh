#!/usr/bin/env bash
# Read-only collection of device information over ADB.
# Never writes to the device. Targets one serial explicitly (other ADB devices may be attached).
# Usage: scripts/collect/collect_device_info.sh <adb-serial> [outdir]
set -euo pipefail
SERIAL="${1:?usage: $0 <adb-serial> [outdir]}"
OUT="${2:-investigation/device/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
A() { adb -s "$SERIAL" "$@"; }

[ "$(A shell getprop ro.product.device | tr -d '\r')" = "Helios" ] || { echo "Device $SERIAL is not a Smart Clock 2 (Helios). Aborting."; exit 1; }

A shell getprop                          > "$OUT/getprop.txt"
A shell cat /proc/cpuinfo                > "$OUT/cpuinfo.txt"
A shell cat /proc/meminfo                > "$OUT/meminfo.txt"
A shell cat /proc/version                > "$OUT/proc_version.txt"
A shell mount                            > "$OUT/mount.txt"
A shell df -h                            > "$OUT/df.txt" 2>&1 || true
A shell ls -l /dev/block/by-name/        > "$OUT/by-name.txt" 2>&1 || true
A shell cat /vendor/etc/fstab.mt8167     > "$OUT/fstab.mt8167" 2>&1 || true
A shell wm size                          > "$OUT/wm.txt"; A shell wm density >> "$OUT/wm.txt"
A shell getevent -lp                     > "$OUT/getevent.txt" 2>&1 || true
for f in -s -3 -d -u; do A shell pm list packages -f $f > "$OUT/packages${f}.txt"; done
for ns in global secure system; do A shell settings list $ns > "$OUT/settings_$ns.txt"; done
for s in webviewupdate meminfo package activity input window usb battery DockObserver device_policy statusbar; do
  A shell dumpsys $s > "$OUT/dumpsys_$s.txt" 2>&1 || true
done
A shell service list                     > "$OUT/service_list.txt"
A shell ps -A -o PID,RSS,USER,NAME       > "$OUT/ps.txt"
A shell ime list -a -s                   > "$OUT/ime.txt"
A shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/home_activities.txt"
A logcat -d -v time                      > "$OUT/logcat.txt" 2>&1 || true
echo "Collected into $OUT (contains the device serial — keep private)."
