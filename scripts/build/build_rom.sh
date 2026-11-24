#!/usr/bin/env bash
# Full ROM build from the stock backup + rom/manifest.json. Offline; never touches the device.
# Outputs firmware/work/{super.img, vbmeta_system.img, vbmeta_disabled.img, BUILD.txt}.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$ROOT"
. scripts/lib_backup.sh
# (Re)extract the stock images when the backup changed.
SUPER_SUM=$(shasum -a 256 "$BACKUP/partitions/super.bin" | cut -d' ' -f1)
if [ "$(cat firmware/original/.super.sha256 2>/dev/null)" != "$SUPER_SUM" ]; then
  rm -rf firmware/original && mkdir -p firmware/original
  python3 -I scripts/build/lp_unpack.py "$BACKUP/partitions/super.bin" firmware/original
  echo "$SUPER_SUM" > firmware/original/.super.sha256
fi
python3 -I scripts/build/check_firmware.py firmware/original/system.img
# Own apps: build from source when the Android SDK is present, else use the release's prebuilt APKs.
if [ -d "${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/build-tools/34.0.0" ] && [ "${USE_PREBUILT_APPS:-}" != 1 ]; then
  scripts/build/build_apps.sh
  scripts/build/build_overlays.sh
else
  python3 -I scripts/build/fetch_inputs.py ownapps
fi
python3 -I scripts/build/fetch_inputs.py check
[ -x "$HOME/Library/Android/sdk/build-tools/34.0.0/aapt2" ] && python3 -I scripts/build/check_privapp.py out/apps/ClockBridge.apk org.kioskclock.bridge rom/product/etc/permissions/privapp-permissions-kioskclock.xml || true
rm -f firmware/work/product.img firmware/work/super.img
python3 -I scripts/build/build_product.py
scripts/build/build_vbmeta.sh
# vbmeta_system padded to its 4 MiB partition so the flash writes the whole partition.
python3 -I -c "import sys;p='firmware/work/vbmeta_system.img';d=open(p,'rb').read();open('firmware/work/vbmeta_system.part.img','wb').write(d+b'\0'*(4194304-len(d)))"
python3 -I scripts/build/lp_repack.py "$BACKUP/partitions/super.bin" firmware/work/super.img product firmware/work/product.avb.img
{
  echo "Kiosk Clock ROM build $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "git $(git rev-parse --short HEAD)$(git diff --quiet || echo '-dirty')"
  echo "base backup: $BACKUP"
  echo; echo "flash images (sha256):"
  for f in super.img vbmeta_system.part.img vbmeta_disabled.img; do
    echo "  $(shasum -a 256 firmware/work/$f | cut -d' ' -f1)  $f  $(wc -c < firmware/work/$f | tr -d " ") bytes"
  done
  echo; echo "product changes:"; sed 's/^/  /' firmware/work/product.changes.txt
} > firmware/work/BUILD.txt
cat firmware/work/BUILD.txt
