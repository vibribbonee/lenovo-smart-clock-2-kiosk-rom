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
MTK="${MTK:-$(command -v mtk || echo /opt/homebrew/bin/mtk)}"
. "$ROOT/scripts/lib_backup.sh"
BK="$BACKUP"
W="$ROOT/firmware/work"
MAN="$BACKUP_MANIFEST"
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
echo "Now: with the clock fully off (power adapter and pogo cable unplugged), hold BOTH volume keys and plug the pogo cable and power in."
(cd "$LOG" && $MTK script "$S" 2>&1 | tee "$LOG/mtk_output.txt")

if [ "$MODE" = rehearsal ]; then
  a=$(shasum -a 256 "$BK/partitions/logo.bin" | cut -d' ' -f1); b=$(shasum -a 256 "$LOG/logo.readback.bin" | cut -d' ' -f1)
  if [ "$a" = "$b" ]; then echo "REHEARSAL OK: logo written and read back identically ($a)"; echo "$a" > "$LOG/REHEARSAL_OK"
  else echo "REHEARSAL MISMATCH: $a vs $b"; fi
fi
[ -n "${SUDO_USER:-}" ] && chown -R "$SUDO_USER" "$LOG"
echo "log: $LOG"
# mtkclient exits 0 even when it never reached the clock: count what it actually wrote.
need=$(grep -c '^w ' "$S" || true)
got=$(tr '\r' '\n' < "$LOG/mtk_output.txt" | grep -c '^Wrote ' || true)
if [ "$need" -gt 0 ] && [ "$got" -lt "$need" ]; then
  if [ "$got" -eq 0 ]; then echo "NOTHING WAS WRITTEN: mtkclient did not get a connection to the clock."
  else echo "INCOMPLETE: only $got of $need partitions were written. Run the same command again before rebooting the clock."; fi
  if grep -q "Please disconnect" "$LOG/mtk_output.txt"; then
    echo "The clock was still in download mode from an earlier attempt. Switch it fully off: unplug BOTH the power"
    echo "adapter and the pogo USB cable (the cable powers the clock too) and wait 10 s. Run this again; only after the"
    echo "'Now: …' line, hold both volume keys and plug the pogo cable and power back in."
  fi
  exit 3
fi
