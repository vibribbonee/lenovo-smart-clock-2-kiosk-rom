# Lenovo Smart Clock 2 — Kiosk Appliance ROM

Turning a Lenovo Smart Clock 2 (CD‑24502F, MT8167S, Android 10, 1 GB RAM, 32‑bit) into a Google‑free, single‑purpose kiosk running **Kiosk Satellite**, with a modern WebView, an offline keyboard, a swipe‑up navigation bar, Wi‑Fi settings and the dock's night light / phone‑charging state — and surviving a factory reset.

**Status: Stage 1 backup verified, firmware unpacked, Stage 2 validation done. Device changes so far (all reversible): Cast and Google OTA updater disabled, MicAnchor installed. Next: Stage 3 build pipeline.**

## Architecture (planned)

```
Stock firmware 1.2.2.627 (kept byte-identical: preloader, LK, boot/kernel, vendor, identity partitions)
└─ super ─ product (modified)
     ├─ Kiosk Satellite        sole HOME app → auto-start + crash recovery by Android itself
     ├─ Google WebView 156+    made a valid provider by a framework RRO overlay; updatable via adb/app
     ├─ Simple Keyboard        offline IME, default after reset
     ├─ KioskNav (ours)        accessibility edge-swipe → Back / Home / Settings bar (no SystemUI exists)
     ├─ DockBridge (ours)      whitelisted calls into Lenovo's charge_base service (light, state)
     ├─ KioskProvisioner (ours) idempotent first-boot settings; AOSP Provision skips Google setup
     └─ removed after testing: Assistant, Cast (mediashell), Google OTA updater, assistant launcher
Bootloader unlocked, vbmeta verification disabled (stock vbmeta images with flags), BROM backup as safety net
```

Why not root/Magisk: modules live in /data and vanish on factory reset. Why not a GSI: Lenovo's dock service lives in the stock framework. Details in [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md).

## Documentation
| Doc | Content |
|---|---|
| [HARDWARE](docs/HARDWARE.md) | Verified hardware facts and what's still unknown |
| [FIRMWARE](docs/FIRMWARE.md) | Build, boot state, Google/OEM stack, update risks |
| [PARTITIONS](docs/PARTITIONS.md) | 28 partitions, identity/calibration set |
| [BACKUP_AND_RECOVERY](docs/BACKUP_AND_RECOVERY.md) | BROM backup, verification, restore, limits |
| [PACKAGE_INVENTORY](docs/PACKAGE_INVENTORY.md) | 109 packages classified |
| [KIOSK_SATELLITE](docs/KIOSK_SATELLITE.md) | APK analysis and integration |
| [WEBVIEW](docs/WEBVIEW.md) | Provider options and design |
| [KEYBOARD](docs/KEYBOARD.md) | Simple Keyboard |
| [NAVIGATION](docs/NAVIGATION.md) | Why native gestures are impossible here, and the replacement |
| [DOCK](docs/DOCK.md) | charge_base service, decoded transaction codes |
| [IMPLEMENTATION_PLAN](docs/IMPLEMENTATION_PLAN.md) | Stages and approval gates |
| [TEST_PLAN](docs/TEST_PLAN.md) | Tests required before "done" |
| [IMAGE_ANALYSIS](docs/IMAGE_ANALYSIS.md) | super/ext4/AVB/WebView list from the backup |
| [PERFORMANCE_BASELINE](docs/PERFORMANCE_BASELINE.md) | CPU, memory, mic bug and fix |
| [STAGE2_RESULTS](docs/STAGE2_RESULTS.md) | What was tested on stock and what it means for the ROM |
| [SECURITY_NOTES](docs/SECURITY_NOTES.md) | smartclock2tool (runtime root, unauthenticated ADB Wi‑Fi, LAN agent) |

## Development workflow
1. **Collect** (read‑only): `scripts/collect/collect_device_info.sh <serial>`
2. **Back up** (approval required; macOS needs sudo for BROM): `sudo I_APPROVE_BROM_READ=yes scripts/backup/mtk_backup_readonly.sh backups`, then verify (see `docs/BACKUP_AND_RECOVERY.md` §6)
3. **Extract** `super` offline: `scripts/build/lp_unpack.py <super.bin> firmware/original` (never modified afterwards); inspect with `avb_info.py`, `axml_dump.py`, `debugfs`.
4. **Build** (Stage 3): `scripts/build/` assembles `firmware/work/super.img` from `firmware/original/` + `rom/manifest.yaml`; every change is listed in the manifest with hashes.
5. **Flash** — only manually, only after approval, never from a script that runs unattended.
6. **Test** per `docs/TEST_PLAN.md`; record results in `docs/test-results/`.

## Rules
Never flash or erase automatically · never format userdata · never overwrite backups · never touch calibration/identity partitions · never remove unknown components · never claim untested functionality. `backups/`, `investigation/` and `firmware/` are git‑ignored and must never be uploaded (they contain the device serial and identity data).

Reference: upstream rooting guide in `upstream/LenovoSmartClock2` (README only) and the XDA thread "Lets get started on the Lenovo Smart Clock 2".
