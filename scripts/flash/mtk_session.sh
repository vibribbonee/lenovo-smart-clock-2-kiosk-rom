#!/usr/bin/env bash
# Runs ONE mtkclient BROM session with a fixed, named command list. Writes to the device:
# only run after reviewing docs/FLASHING.md, at the clock, with explicit approval.
#
#   sudo I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh rehearsal   # write logo back with its own bytes, re-read
#   sudo I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh flash       # erase data, unlock, write super + vbmeta_system
#   sudo I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh update      # already unlocked: write super + vbmeta_system, keep data
#   sudo I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh disable-verity   # fallback: stock vbmeta with verification off
#   sudo I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh restore-stock    # stock super, vbmeta, vbmeta_system back
#
# Every input file is checked against its recorded SHA-256 before mtkclient starts.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$ROOT"
MODE="${1:?mode: rehearsal | flash | update | disable-verity | restore-stock}"
[ "${I_AM_AT_THE_CLOCK:-}" = "yes" ] || { echo "Set I_AM_AT_THE_CLOCK=yes (see docs/FLASHING.md)"; exit 1; }
MTK=/opt/homebrew/bin/mtk
BK="$ROOT/backups/20261123-233949-1.2.2.627"
W="$ROOT/firmware/work"
MAN="$ROOT/backups/20261123-233949-1.2.2.627.MANIFEST.tsv"
LOG="$ROOT/backups/flash-logs/$(date +%Y%m%d-%H%M%S)-$MODE"; mkdir -p "$LOG"

want_backup() {  # verify a backup partition dump against the backup manifest
  local name="$1" sum; sum=$(awk -F'\t' -v n="$name" '$1==n{print $5}' "$MAN")
  [ -n "$sum" ] || { echo "no manifest entry for $name"; exit 1; }
  [ "$(shasum -a 256 "$BK/partitions/$name.bin" | cut -d' ' -f1)" = "$sum" ] || { echo "backup $name.bin does not match manifest"; exit 1; }
  echo "verified backup $name.bin"
}
want_build() {  # verify a build output against BUILD.txt
  local f="$1" sum; sum=$(awk -v f="$f" '$2==f{print $1}' "$W/BUILD.txt")
  [ -n "$sum" ] || { echo "no BUILD.txt entry for $f"; exit 1; }
  [ "$(shasum -a 256 "$W/$f" | cut -d' ' -f1)" = "$sum" ] || { echo "$f does not match BUILD.txt"; exit 1; }
  echo "verified build $f"
}

S="$LOG/script.txt"
case "$MODE" in
  rehearsal)
    want_backup logo
    printf 'w logo %s\nr logo %s\nreset\n' "$BK/partitions/logo.bin" "$LOG/logo.readback.bin" > "$S" ;;
  flash)
    want_build super.img; want_build vbmeta_system.part.img
    printf 'e metadata,userdata,md_udc\nda seccfg unlock\nw super %s\nw vbmeta_system %s\nreset\n' \
      "$W/super.img" "$W/vbmeta_system.part.img" > "$S" ;;
  update)
    # Already unlocked: write only super + vbmeta_system. No erase, no seccfg change; /data is kept.
    want_build super.img; want_build vbmeta_system.part.img
    printf 'w super %s\nw vbmeta_system %s\nreset\n' "$W/super.img" "$W/vbmeta_system.part.img" > "$S" ;;
  disable-verity)
    want_build vbmeta_disabled.img
    printf 'w vbmeta %s\nreset\n' "$W/vbmeta_disabled.img" > "$S" ;;
  restore-stock)
    want_backup super; want_backup vbmeta; want_backup vbmeta_system
    printf 'w super %s\nw vbmeta %s\nw vbmeta_system %s\nreset\n' \
      "$BK/partitions/super.bin" "$BK/partitions/vbmeta.bin" "$BK/partitions/vbmeta_system.bin" > "$S" ;;
  *) echo "unknown mode $MODE"; exit 1 ;;
esac
echo "--- mtkclient will run:"; cat "$S"; echo "---"
echo "Now: power off the clock, hold BOTH volume keys, plug power in (pogo USB attached)."
(cd "$LOG" && $MTK script "$S" 2>&1 | tee "$LOG/mtk_output.txt")

if [ "$MODE" = rehearsal ]; then
  a=$(shasum -a 256 "$BK/partitions/logo.bin" | cut -d' ' -f1); b=$(shasum -a 256 "$LOG/logo.readback.bin" | cut -d' ' -f1)
  [ "$a" = "$b" ] && echo "REHEARSAL OK: logo written and read back identically ($a)" || echo "REHEARSAL MISMATCH: $a vs $b"
fi
[ -n "${SUDO_USER:-}" ] && chown -R "$SUDO_USER" "$LOG"
echo "log: $LOG"
