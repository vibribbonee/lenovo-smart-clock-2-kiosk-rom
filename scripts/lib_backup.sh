# Sourced by scripts: resolves BACKUP (the backup directory) for this checkout.
# Order: $BACKUP env var, else backups/.last_backup_dir (written by the backup step).
_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -z "${BACKUP:-}" ] && [ -f "$_root/backups/.last_backup_dir" ]; then BACKUP="$(cat "$_root/backups/.last_backup_dir")"; fi
case "${BACKUP:-}" in /*) ;; "") ;; *) BACKUP="$_root/$BACKUP" ;; esac
[ -n "${BACKUP:-}" ] && [ -f "$BACKUP/partitions/super.bin" ] || { echo "No backup found. Run: ./clockrom.sh backup  (or set BACKUP=/path/to/backup-dir)"; exit 1; }
BACKUP_MANIFEST="$BACKUP.MANIFEST.tsv"
export BACKUP BACKUP_MANIFEST
