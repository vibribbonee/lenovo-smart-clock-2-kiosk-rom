#!/usr/bin/env bash
# Lenovo Smart Clock 2 kiosk ROM — guided all-in-one script.
#
#   ./clockrom.sh doctor                 check tools, fetch avbtool
#   ./clockrom.sh backup                 read-only BROM backup of YOUR clock + verification (do this first)
#   ./clockrom.sh build [--webview FILE | --webview-from-device | --no-webview]
#                                        fetch + verify APKs, build the ROM from your backup (offline)
#   ./clockrom.sh flash                  rehearsal, then first flash (unlock + wipe) or update (keeps data)
#   ./clockrom.sh restore                write your stock super/vbmeta back
#
# Nothing is written to the clock without you typing the confirmation shown on screen.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"; cd "$ROOT"
AVBTOOL_URL="https://android.googlesource.com/platform/external/avb/+/refs/heads/main/avbtool.py?format=TEXT"
AVBTOOL_SHA=e5a664a38db623da00f080219bc0ee60a640a9dc4a872803616fae4938ac749b
B=$'\e[1m'; R=$'\e[0m'; RED=$'\e[31m'; GRN=$'\e[32m'
say()  { printf '%s\n' "$*"; }
step() { printf '\n%s== %s ==%s\n' "$B" "$*" "$R"; }
die()  { printf '%s%s%s\n' "$RED" "$*" "$R" >&2; exit 1; }
confirm() {  # confirm "<what will happen>" <word>
  printf '\n%s%s%s\nType %s%s%s to continue, anything else to stop: ' "$B" "$1" "$R" "$B" "$2" "$R"
  read -r a; [ "$a" = "$2" ] || die "Stopped. Nothing was written."
}
MTK="${MTK:-$(command -v mtk || true)}"

doctor() {
  step "Checking tools"
  local miss=()
  command -v python3 >/dev/null || miss+=("python3")
  command -v openssl >/dev/null || miss+=("openssl")
  command -v shasum  >/dev/null || miss+=("shasum (perl)")
  local dbg; for dbg in /opt/homebrew/opt/e2fsprogs/sbin/debugfs /usr/local/opt/e2fsprogs/sbin/debugfs /sbin/debugfs /usr/sbin/debugfs; do [ -x "$dbg" ] && break; done
  [ -x "$dbg" ] || miss+=("e2fsprogs (debugfs, resize2fs)  →  macOS: brew install e2fsprogs   Debian/Ubuntu: apt install e2fsprogs")
  [ -n "$MTK" ] || miss+=("mtkclient  →  https://github.com/bkerler/mtkclient (pip install, then 'mtk' on PATH; or set MTK=/path/to/mtk)")
  command -v adb >/dev/null || say "  (optional) adb not found — only needed for --webview-from-device and checking the clock afterwards"
  if [ ! -f tools/avb/avbtool.py ] || [ "$(shasum -a 256 tools/avb/avbtool.py | cut -d' ' -f1)" != "$AVBTOOL_SHA" ]; then
    say "  fetching avbtool from AOSP …"; mkdir -p tools/avb
    python3 -I -c "import base64,sys,urllib.request;open(sys.argv[2],'wb').write(base64.b64decode(urllib.request.urlopen(sys.argv[1],timeout=60).read()))" \
      "$AVBTOOL_URL" tools/avb/avbtool.py.new
    if [ "$(shasum -a 256 tools/avb/avbtool.py.new | cut -d' ' -f1)" = "$AVBTOOL_SHA" ]; then mv tools/avb/avbtool.py.new tools/avb/avbtool.py
    else say "  ${RED}AOSP's avbtool changed since this project was tested (sha256 differs).${R} Using it anyway is likely fine; it's kept as tools/avb/avbtool.py."
         mv tools/avb/avbtool.py.new tools/avb/avbtool.py; fi
  fi
  if [ ${#miss[@]} -gt 0 ]; then say "Missing:"; printf '  - %s\n' "${miss[@]}"; die "Install the above, then run ./clockrom.sh doctor again."; fi
  say "${GRN}All tools present.${R}"
}

need_root_for_mtk() {  # mtkclient needs raw USB access (sudo on macOS; udev rules or sudo on Linux)
  [ "$(id -u)" = 0 ] && return
  say "mtkclient needs administrator rights for USB access; you'll be asked for your password."
}
run_mtk() { need_root_for_mtk; sudo -E env MTK="$MTK" "$@"; }

backup() {
  doctor
  step "Backup (read-only)"
  cat <<EOF
This reads every partition of your clock except userdata (your personal data) into backups/.
It only READS. It is your way back to stock, and the ROM is built from it — keep it, keep it private
(it contains your clock's serial, Wi-Fi MAC and calibration), and copy it somewhere safe afterwards.

Connect the pogo-pin USB cable to this computer, unplug the clock's power.
When asked: hold BOTH volume keys and plug the power in (BROM mode). It takes ~15 minutes.
EOF
  confirm "Start the read-only backup?" backup
  mkdir -p backups
  run_mtk I_APPROVE_BROM_READ=yes scripts/backup/mtk_backup_readonly.sh backups
  local dir; dir="$(cat backups/.last_backup_dir)"
  [ "$(id -u)" = 0 ] || sudo chown -R "$(id -un)" backups
  step "Verifying the backup"
  local others; others=$(ls -d backups/*-1.2.2.627 2>/dev/null | grep -v "$dir" | tail -1 || true)
  if [ -n "$others" ]; then python3 -I scripts/backup/verify_backup.py "$dir" --second "$ROOT/$others"
  else python3 -I scripts/backup/verify_backup.py "$dir"
       say "Tip: run ./clockrom.sh backup a second time; the check then compares both reads byte-for-byte."; fi
  chmod -R a-w "$dir"
  say "${GRN}Backup: $dir${R} (made read-only). Copy it, plus its .MANIFEST.tsv, to a safe place."
}

build() {
  doctor
  . scripts/lib_backup.sh
  step "Inputs"
  local wv=()
  while [ $# -gt 0 ]; do case "$1" in
    --webview) wv=(--apk "$2"); shift 2 ;;
    --webview-from-device) wv=(--from-device); shift ;;
    --no-webview) wv=(--skip); shift ;;
    *) die "unknown option $1" ;;
  esac; done
  python3 -I scripts/build/fetch_inputs.py kiosk
  python3 -I scripts/build/fetch_inputs.py keyboard
  python3 -I scripts/build/fetch_inputs.py webview ${wv[@]+"${wv[@]}"}
  step "Building from $BACKUP"
  scripts/build/build_rom.sh
  say "${GRN}Built.${R} Next: ./clockrom.sh flash"
  [ -f keys/avb_kioskclock.pem ] && say "Your signing key is keys/avb_kioskclock.pem — back up keys/ (later updates must use the same key)."
}

flash() {
  . scripts/lib_backup.sh
  [ -f firmware/work/BUILD.txt ] || die "No build yet: ./clockrom.sh build"
  local logs=backups/flash-logs
  step "1/2 Rehearsal"
  if ls "$logs"/*-rehearsal/REHEARSAL_OK >/dev/null 2>&1; then
    say "A restore rehearsal already passed on this computer — skipping."
  else
    cat <<EOF
Before writing anything big, we prove that writing and reading back works on your clock: the boot
logo partition is written with its own bytes from your backup and read back. Harmless.
Pogo USB connected, clock power unplugged; when asked, hold BOTH volume keys and plug power in.
EOF
    confirm "Run the rehearsal?" rehearsal
    run_mtk I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh rehearsal
    ls "$logs"/*-rehearsal/REHEARSAL_OK >/dev/null 2>&1 || die "Rehearsal did not pass. Don't flash; see docs/FLASHING.md."
  fi
  step "2/2 Flash"
  local mode
  if ls "$logs"/*-flash/mtk_output.txt >/dev/null 2>&1; then mode=update; else mode=flash; fi
  if [ "$mode" = flash ]; then
    cat <<EOF
${B}First flash.${R} This will:
  - ${RED}erase userdata${R} (a factory reset: Wi-Fi, accounts, everything on the clock)
  - unlock the bootloader (an orange "unlocked" warning shows at every boot from now on)
  - write the new super (system/vendor/product) and your signed vbmeta_system
Your stock firmware stays restorable with ./clockrom.sh restore.
If this clock was flashed before from another computer, choose update instead: type 'update'.
EOF
    printf 'Type %sflash%s (first time) or %supdate%s (already flashed, keep data): ' "$B" "$R" "$B" "$R"; read -r mode
    [ "$mode" = flash ] || [ "$mode" = update ] || die "Stopped. Nothing was written."
  else
    say "This clock was flashed before: ${B}update${R} writes super + vbmeta_system and keeps your data."
    confirm "Write the new build to the clock?" update
  fi
  say "When asked: hold BOTH volume keys and plug power in."
  run_mtk I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh "$mode"
  say "${GRN}Done.${R} First boot after a flash takes a few minutes (orange Lenovo logo). Then see docs/FLASHING.md → After flashing."
}

restore() {
  . scripts/lib_backup.sh
  cat <<EOF
This writes your stock super, vbmeta and vbmeta_system from $BACKUP back to the clock.
The bootloader stays unlocked (relock only after a full stock restore; see docs/FLASHING.md).
EOF
  confirm "Restore stock firmware?" restore
  run_mtk I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh restore-stock
}

case "${1:-}" in
  doctor) doctor ;;
  backup) backup ;;
  build) shift; build "$@" ;;
  flash) flash ;;
  restore) restore ;;
  *) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
