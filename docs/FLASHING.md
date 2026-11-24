# Flashing the ROM (Stages 1b and 4)

All device writes go through `scripts/flash/mtk_session.sh`. It runs one BROM session with a fixed command list, refuses to start unless every input matches its recorded SHA-256 (backup manifest or `firmware/work/BUILD.txt`), and logs to `backups/flash-logs/`. It must be started by you with `sudo` (macOS needs it for BROM, see BACKUP_AND_RECOVERY.md §6), at the clock.

BROM entry (same as the backup): pogo USB cable attached, power unplugged, hold **both** volume keys, plug power in, hold ~10 s.

## What gets written
| Partition | Content | Changed vs stock |
|---|---|---|
| `super` | `firmware/work/super.img`: stock system and vendor at their stock positions (byte-identical), new 816 MiB product in previously free space, slot-0 metadata updated, slot 1 untouched | product |
| `vbmeta_system` | signed with our key (`keys/avb_kioskclock.pem`): stock system hashtree descriptor + new product hashtree | yes |
| `seccfg` | lock state → **unlocked** (mtkclient `da seccfg unlock`) | yes |
| `userdata`, `md_udc` (/metadata), `metadata` | **erased** — required: unlocking changes the keys that protect the encrypted data | yes (data loss) |
| everything else (preloader, lk, boot, recovery, dtbo, vendor, vbmeta, nvram, nvdata, proinfo, persist, …) | untouched | no |

## Stage 1b — restore rehearsal (do first)
```bash
sudo I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh rehearsal
```
Writes the backup's `logo.bin` back to `logo` (identical bytes) and reads it again. Expected: `REHEARSAL OK`. This proves the write path works on this Mac/cable/mtkclient before we depend on it. The clock boots normally afterwards.

## Stage 4 — unlock and flash
```bash
sudo I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh flash
```
Sequence (one session): erase `metadata,userdata,md_udc` → `da seccfg unlock` → write `super` → write `vbmeta_system` → reset.

### Expected first boot
1. LK shows an **orange-state warning** (unlocked bootloader) and waits ~5 s or for a key — normal from now on.
2. First boot takes longer (dexopt of new apps, /data created fresh).
3. AOSP Provision marks the device set up; Kiosk Satellite starts as the only home app; ClockBridge starts before it (persistent) and holds the mic at 48 kHz.
4. ADB is **off** after the wipe. Enable it via the KioskNav Settings button → About → tap build number 7× → Developer options → USB debugging (and Wi‑Fi ADB with `adb tcpip`).

### If it does not boot
| Symptom | Action |
|---|---|
| Bootloop or "verification failed" right after the orange warning (LK refuses our vbmeta_system key) | `sudo I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh disable-verity` (stock vbmeta with verification disabled — what the upstream guide uses) |
| Boots to Android but crashes / loops at the boot animation (framework issue, e.g. permissions) | Capture logs: `adb logcat` only works if ADB was on, so go to `restore-stock`, then investigate offline |
| Anything else / no idea | `sudo I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh restore-stock` — stock super, vbmeta, vbmeta_system from Backup #1. The bootloader stays unlocked (orange) but runs stock Android. |
| BROM unreachable | Hardware/contact problem: re-seat pogo pins; BROM is in mask ROM and cannot be erased by any of these writes. |

Re-locking (back to green, full stock) is possible later with the backup's `seccfg` plus stock images, and needs another data wipe; not scripted yet.
