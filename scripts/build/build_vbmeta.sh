#!/usr/bin/env bash
# AVB for the modified product partition.
#  - product: adds a dm-verity hashtree footer (no FEC: AOSP's fec tool isn't available on macOS),
#    with the same os_version/security_patch props as stock.
#  - vbmeta_system: system descriptors from the untouched stock system.img + new product descriptors,
#    signed with our own key (keys/avb_kioskclock.pem). The stock top-level vbmeta still chains
#    vbmeta_system to Lenovo's key, so on an UNLOCKED bootloader this boots "orange" with dm-verity
#    still enforcing our hashes.
#  - vbmeta_disabled (fallback only): stock top-level vbmeta re-made with flags=2 (verification
#    disabled), for the case where LK refuses the key mismatch. Not used unless needed.
# Outputs in firmware/work/. Never modifies firmware/original or backups.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A() { python3 -I "$ROOT/tools/avb/avbtool.py" "$@"; }
W="$ROOT/firmware/work"; O="$ROOT/firmware/original"
B="$ROOT/backups/20261123-233949-1.2.2.627/partitions"
KEY="$ROOT/keys/avb_kioskclock.pem"
PRODUCT_PARTITION_MIB=816

[ -f "$W/product.img" ] || { echo "run build_product.py first"; exit 1; }
if [ ! -f "$KEY" ]; then
  umask 077; openssl genrsa -out "$KEY" 2048 2>/dev/null
  echo "created AVB key $KEY (back it up with keys/)"
fi
A extract_public_key --key "$KEY" --output "$ROOT/keys/avb_kioskclock.pubkey.bin"

# product: hashtree footer on a copy, so a re-run always starts from build_product.py output
cp "$W/product.img" "$W/product.avb.img"
A add_hashtree_footer --image "$W/product.avb.img" --partition_name product \
  --partition_size $((PRODUCT_PARTITION_MIB * 1024 * 1024)) --hash_algorithm sha256 \
  --do_not_generate_fec \
  --prop com.android.build.product.os_version:10 \
  --prop com.android.build.product.security_patch:2020-10-05

# vbmeta_system signed with our key
A make_vbmeta_image --output "$W/vbmeta_system.img" --algorithm SHA256_RSA2048 --key "$KEY" \
  --rollback_index 0 --padding_size 4096 \
  --include_descriptors_from_image "$O/system.img" \
  --include_descriptors_from_image "$W/product.avb.img"

# Fallback: stock vbmeta with verification disabled (flags=2), same descriptors, stock image untouched.
python3 -I - "$B/vbmeta.bin" "$W/vbmeta_disabled.img" <<'EOF'
import struct, sys
d = bytearray(open(sys.argv[1], 'rb').read())
assert d[:4] == b'AVB0'
struct.pack_into('>I', d, 120, 2)   # flags: AVB_VBMETA_IMAGE_FLAGS_VERIFICATION_DISABLED
open(sys.argv[2], 'wb').write(bytes(d))
EOF

for f in product.avb.img vbmeta_system.img vbmeta_disabled.img; do
  printf "%-20s %10s bytes  sha256 %s\n" "$f" "$(stat -f %z "$W/$f")" "$(shasum -a 256 "$W/$f" | cut -c1-16)"
done
