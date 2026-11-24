#!/usr/bin/env python3
"""DEBUG builds only: firmware/work/system.img = stock system + /adb_keys (this Mac's ADB public key).

adbd on Android 10 trusts keys from /adb_keys and /data/misc/adb/adb_keys. This firmware has no
SystemUI, so the usual "Allow USB debugging?" dialog cannot appear; a key in the image is the only
way to get an authorized ADB connection right after a wipe. Owner 0:0, mode 0644, label
u:object_r:adb_keys_file:s0 (from the stock plat_file_contexts). Size is unchanged, so system keeps
its extent in super. Release builds leave system byte-identical to stock.
"""
import os, subprocess, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_product import E2, ext4_size, run, debugfs  # noqa: E402

KEY = os.environ.get("ADB_PUBKEY", os.path.expanduser("~/.android/adbkey.pub"))


def main():
    if not os.path.isfile(KEY):
        sys.exit(f"no ADB public key at {KEY}")
    src = os.path.join(ROOT, "firmware", "original", "system.img")
    out = os.path.join(ROOT, "firmware", "work", "system.img")
    fs = ext4_size(src)
    with open(src, "rb") as i, open(out, "wb") as o:
        left = fs
        while left:
            b = i.read(min(left, 1 << 24)); o.write(b); left -= len(b)
    label = os.path.join(ROOT, "firmware", "work", "label-adb")
    with open(label, "wb") as f:
        f.write(b"u:object_r:adb_keys_file:s0\0")
    debugfs(out, [f"write {KEY} /adb_keys", "sif /adb_keys mode 0100644", "sif /adb_keys uid 0",
                  "sif /adb_keys gid 0", f"ea_set -f {label} /adb_keys security.selinux"], write=True)
    os.unlink(label)
    r = run(f"{E2}/e2fsck", "-fn", out, check=False)
    print(r.stdout.strip().splitlines()[-1])
    if r.returncode != 0:
        sys.exit("e2fsck found problems")
    print(f"done: {out} with /adb_keys from {KEY}")


if __name__ == "__main__":
    main()
