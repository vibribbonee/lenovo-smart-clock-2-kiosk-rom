#!/usr/bin/env bash
# Stage 1 — READ-ONLY full backup via MediaTek BROM (mtkclient), in ONE BROM session.
#
# Only READ commands are generated (printgpt, gpt, rf, rl). The script refuses to run if the
# generated command list contains anything else, and refuses to overwrite an existing backup.
#
# Usage:  I_APPROVE_BROM_READ=yes scripts/backup/mtk_backup_readonly.sh <backup-root>
# Then:   clock unplugged from power, pogo USB cable attached to the Mac,
#         hold Vol+ and plug in power. mtkclient connects and runs everything.
set -euo pipefail
ROOT="${1:?usage: $0 <backup-root>}"
[ "${I_APPROVE_BROM_READ:-}" = "yes" ] || { echo "Set I_APPROVE_BROM_READ=yes after reviewing docs/BACKUP_AND_RECOVERY.md"; exit 1; }
MTK="${MTK:-/opt/homebrew/bin/mtk}"
ROOT="$(cd "$ROOT" && pwd)"
DEST="$ROOT/$(date +%Y%m%d-%H%M%S)-1.2.2.627"
[ -e "$DEST" ] && { echo "$DEST exists — refusing to overwrite"; exit 1; }
mkdir -p "$DEST/partitions" "$DEST/hw" "$DEST/gpt" "$DEST/logs"
case "$DEST" in *" "*) echo "Path contains spaces; mtkclient script mode can't handle that"; exit 1;; esac

SCRIPT="$DEST/logs/mtk_script.txt"
cat > "$SCRIPT" <<EOF
printgpt
gpt $DEST/gpt
rf $DEST/hw/boot1.bin --parttype boot1
rf $DEST/hw/boot2.bin --parttype boot2
rl $DEST/partitions --skip userdata
rf $DEST/hw/full_user.bin
reset
EOF
# Safety: only read commands (and the final reset) are allowed.
if grep -vE '^(printgpt|gpt |rf |rl |reset$)' "$SCRIPT" | grep -q .; then
  echo "Refusing: script contains a non-read command"; cat "$SCRIPT"; exit 1
fi

{ echo "mtkclient: $($MTK --help 2>&1 | grep -m1 'MTK Flash' || true)"; echo "python: $(python3 --version)"; echo "host: $(uname -a)"; date -u; } > "$DEST/logs/environment.txt"
echo "$DEST" > "$ROOT/.last_backup_dir"
echo "Backup dir: $DEST"
echo "Waiting for the clock in BROM mode (unplug power, hold Vol+, plug power in)..."
cd "$DEST/logs"
$MTK script "$SCRIPT" 2>&1 | tee "$DEST/logs/mtk_output.txt"

echo "Hashing..."
( cd "$DEST" && find . -type f \( -name '*.bin' -o -name '*.img' \) | sort | xargs shasum -a 256 > SHA256SUMS )
# macOS needs sudo to detach the CDC driver from the BROM device; hand files back to the user.
if [ "$(id -u)" = "0" ] && [ -n "${SUDO_USER:-}" ]; then
  chown -R "$SUDO_USER" "$DEST" "$ROOT/.last_backup_dir"
fi
echo "Done: $DEST  (next: scripts/backup/verify_backup.sh $DEST)"
