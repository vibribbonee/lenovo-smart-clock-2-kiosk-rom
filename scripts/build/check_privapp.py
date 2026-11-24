#!/usr/bin/env python3
"""Fail the build if a priv-app requests a privileged permission that isn't in its allowlist.

ro.control_privapp_permissions=enforce on this firmware: a missing entry makes system_server
refuse to boot. Privileged permissions are read from the device's own framework (protection
levels dumped once into rom/privileged-permissions.txt), so the check matches the real firmware.
Usage: check_privapp.py <apk> <package> <privapp-permissions.xml>
"""
import os, re, subprocess, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
AAPT2 = os.path.expanduser("~/Library/Android/sdk/build-tools/34.0.0/aapt2")


def main():
    apk, pkg, xml = sys.argv[1:4]
    priv = set(open(os.path.join(ROOT, "rom", "privileged-permissions.txt")).read().split())
    out = subprocess.run([AAPT2, "dump", "permissions", apk], capture_output=True, text=True, check=True).stdout
    requested = set(re.findall(r"uses-permission: name='([^']+)'", out))
    text = open(xml).read()
    block = re.search(r'<privapp-permissions package="%s">(.*?)</privapp-permissions>' % re.escape(pkg), text, re.S)
    allowed = set(re.findall(r'name="([^"]+)"', block.group(1))) if block else set()
    missing = sorted((requested & priv) - allowed)
    if missing:
        sys.exit(f"{pkg}: privileged permissions missing from {os.path.basename(xml)}: {missing}")
    print(f"{pkg}: all {len(requested & priv)} privileged permissions allowlisted")


if __name__ == "__main__":
    main()
