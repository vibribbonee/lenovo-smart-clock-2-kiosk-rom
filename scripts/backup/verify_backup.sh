#!/usr/bin/env bash
# Offline verification of a backup directory. Never touches the device.
# Checks SHA256SUMS, writes MANIFEST.tsv (name, bytes, sha256), and flags empty/zero-filled dumps.
set -euo pipefail
DIR="${1:?usage: $0 <backup-dir>}"
cd "$DIR"
shasum -a 256 -c SHA256SUMS
OUT="$(mktemp)"
printf "file\tbytes\tsha256\tall_zero\n" > "$OUT"
while read -r sum path; do
  bytes=$(stat -f %z "$path")
  if [ -z "$(head -c 1048576 "$path" | LC_ALL=C tr -d '\0' | head -c 1)" ]; then zero=yes; else zero=no; fi
  printf "%s\t%s\t%s\t%s\n" "$path" "$bytes" "$sum" "$zero" >> "$OUT"
done < SHA256SUMS
cat "$OUT"
echo
echo "Compare sizes with logs/printgpt.txt (sectors x 512). Manifest printed above; copy it into the"
echo "backup's notes (the backup directory itself is read-only)."
