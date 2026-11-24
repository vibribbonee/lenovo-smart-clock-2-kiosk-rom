#!/usr/bin/env python3
"""Refuse to build from firmware other than the tested one.

The ROM is built and tested against Lenovo Smart Clock 2 stock firmware 1.2.2.627. Another build may
have a different framework (permissions, hidden services, partition sizes), so building from it could
produce an image that doesn't boot. Usage: check_firmware.py <extracted system.img>
Set KIOSKCLOCK_ALLOW_UNTESTED_FIRMWARE=1 to continue anyway (at your own risk).
"""
import os, subprocess, sys

TESTED = {"ro.product.system.device": "Helios", "ro.build.version.incremental": "LenovoCD-24502F_ROW_1.2.2.627_220105"}
DEBUGFS = next((p for p in ("/opt/homebrew/opt/e2fsprogs/sbin/debugfs", "/usr/local/opt/e2fsprogs/sbin/debugfs",
                            "/sbin/debugfs", "/usr/sbin/debugfs") if os.path.exists(p)), "debugfs")


def main():
    out = subprocess.run([DEBUGFS, "-R", "cat /system/build.prop", sys.argv[1]], capture_output=True, text=True).stdout
    props = dict(l.split("=", 1) for l in out.splitlines() if "=" in l and not l.startswith("#"))
    bad = {k: props.get(k) for k, v in TESTED.items() if props.get(k) != v}
    if not bad:
        print(f"firmware OK: {props.get('ro.build.display.id')}")
        return
    print("This backup is not the tested firmware:")
    for k, v in bad.items():
        print(f"  {k} = {v!r} (tested: {TESTED[k]!r})")
    if os.environ.get("KIOSKCLOCK_ALLOW_UNTESTED_FIRMWARE") == "1":
        print("KIOSKCLOCK_ALLOW_UNTESTED_FIRMWARE=1 set: continuing at your own risk.")
        return
    sys.exit("Stopping. A Smart Clock 2 on another firmware version is untested; see docs/FIRMWARE.md.")


if __name__ == "__main__":
    main()
