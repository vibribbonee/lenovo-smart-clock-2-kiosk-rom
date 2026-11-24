# Lenovo Smart Clock 2 — Kiosk Appliance ROM

Turns a Lenovo Smart Clock 2 (CD‑24502F, MediaTek MT8167S, Android 10, 1 GB RAM, 32‑bit) into a Google‑free, single‑purpose Home Assistant kiosk running **[Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite)**: modern WebView, working wake word, on‑screen navigation and quick settings, dock night light and phone‑charging state, and a setup that survives a factory reset.

> **Disclaimer.** Not affiliated with Lenovo, Google or MediaTek. This repository contains **only scripts, our own apps and documentation — no Lenovo firmware**. You build the ROM from a backup of *your own* clock. Unlocking and flashing can brick a device; the backup/restore path documented here is tested on one unit, not guaranteed for yours. Use at your own risk.

## Status
Built from stock firmware **1.2.2.627** and running on a real clock. Build 2 was flashed and verified; the final build (KioskNav/ClockBridge 0.8.0) is built and verified offline. Still to do on the device: final-build checks and the factory-reset test — see [docs/TODO.md](docs/TODO.md).

## What you get
| | |
|---|---|
| **Kiosk Satellite** | the only home app, starts on boot, recovers after crashes, updated automatically from its GitHub releases (signature-checked) |
| **Google WebView 156** | replaces the 2019 WebView 74; updatable |
| **Voice that works** | ClockBridge holds the mic at 48 kHz stereo from early boot, working around a MediaTek MT8167 capture bug ([details](docs/PERFORMANCE_BASELINE.md)) |
| **Navigation** | swipe up from the bottom edge: Back · Home · Settings (the firmware has no SystemUI) |
| **Quick settings** | swipe down from the top edge: brightness + Auto, volume, dock light, Wi‑Fi, Bluetooth, Night light, screen off (tap to wake), restart |
| **Hardware** | volume keys with click; hold Vol+ = voice assistant, hold Vol− = dock light; mic‑switch indicator (several styles); tap‑on‑top and dock events |
| **Home Assistant** | HTTP API + webhook events for dock light, phone charging, screen, settings — [docs/HOME_ASSISTANT.md](docs/HOME_ASSISTANT.md) |
| **Out of the box** | first boot after flash/reset sets home app, keyboard, nav bar, disables leftovers, no screen timeout — no Google setup |

## Architecture
```
Stock firmware 1.2.2.627 — preloader, LK, boot/kernel, vendor, system and identity partitions byte-identical
└─ super ─ product (rebuilt from the stock product image)
     ├─ Kiosk Satellite, Google WebView, Simple Keyboard     prebuilt, signed by their authors
     ├─ ClockBridge   (apps/ClockBridge, priv-app, persistent) mic anchor, dock (charge_base), HA API/webhook,
     │                                                         button shortcuts, first-boot setup, Kiosk updater
     ├─ KioskNav      (apps/KioskNav)                         nav bar, quick panel, brightness popup, mic indicator
     ├─ framework overlay                                     Google WebView as valid provider
     ├─ etc/ permissions, default permissions, power whitelist, provision.json (first-boot config)
     └─ removed: Google Assistant, Cast receiver, Google OTA updater, unused stock apps
vbmeta_system re-signed with your own AVB key (dm-verity stays on); bootloader unlocked ("orange" state)
```
Why not root/Magisk: modules live in /data and vanish on a factory reset. Why not a GSI/LineageOS: Lenovo's dock service lives in the stock framework. See [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md).

## How to build and flash (summary)
Requirements: macOS or Linux, Python 3, Java 21, Android SDK (platform 29, build-tools 34), `e2fsprogs`, [mtkclient](https://github.com/bkerler/mtkclient), a pogo-pin USB connection to the clock ([upstream guide](https://github.com/ThomasPrior/LenovoSmartClock2)).

1. **Back up your clock** (read-only, BROM): `sudo I_APPROVE_BROM_READ=yes scripts/backup/mtk_backup_readonly.sh backups` — verify as in [docs/BACKUP_AND_RECOVERY.md](docs/BACKUP_AND_RECOVERY.md). Keep it private: it contains your device's identity data.
2. **Get the prebuilt APKs** into `rom/prebuilt/` (Kiosk Satellite, Google WebView, Simple Keyboard; hashes in `rom/prebuilt/SHA256SUMS`) and `tools/avb/avbtool.py` from AOSP.
3. **Build:** `scripts/build/build_rom.sh` → `firmware/work/super.img`, `vbmeta_system.part.img`, `BUILD.txt`. First run creates your signing keys in `keys/` — back them up.
4. **Rehearse a restore, then flash:** `scripts/flash/mtk_session.sh rehearsal`, then `flash` (first time: wipes data, unlocks) or `update` (later builds, keeps data). Procedure and recovery: [docs/FLASHING.md](docs/FLASHING.md).

## Documentation
| Doc | Content |
|---|---|
| [HARDWARE](docs/HARDWARE.md), [FIRMWARE](docs/FIRMWARE.md), [PARTITIONS](docs/PARTITIONS.md) | Verified device facts |
| [BACKUP_AND_RECOVERY](docs/BACKUP_AND_RECOVERY.md), [FLASHING](docs/FLASHING.md) | Backup, restore rehearsal, flash and recovery procedures |
| [IMAGE_ANALYSIS](docs/IMAGE_ANALYSIS.md) | super / ext4 / AVB / WebView provider list |
| [PACKAGE_INVENTORY](docs/PACKAGE_INVENTORY.md), [KIOSK_SATELLITE](docs/KIOSK_SATELLITE.md), [WEBVIEW](docs/WEBVIEW.md), [KEYBOARD](docs/KEYBOARD.md), [NAVIGATION](docs/NAVIGATION.md) | Component decisions |
| [DOCK](docs/DOCK.md) | Dock protocol, decoded `charge_base` codes, hardware key events |
| [PERFORMANCE_BASELINE](docs/PERFORMANCE_BASELINE.md) | CPU, memory and the MT8167 mic bug |
| [STAGE2_RESULTS](docs/STAGE2_RESULTS.md), [ROM1_FIRST_BOOT](docs/ROM1_FIRST_BOOT.md), [TODO](docs/TODO.md) | Test results and open items |
| [HOME_ASSISTANT](docs/HOME_ASSISTANT.md) | API, settings, HA configuration examples |
| [SECURITY_NOTES](docs/SECURITY_NOTES.md) | Notes on third-party root tooling |
| [IMPLEMENTATION_PLAN](docs/IMPLEMENTATION_PLAN.md), [TEST_PLAN](docs/TEST_PLAN.md) | Plan and test checklist |

## Rules followed
Never flash or erase automatically · never format userdata without a deliberate step · never overwrite backups · never touch calibration/identity partitions · never remove unknown components · never claim untested functionality. `backups/`, `investigation/`, `firmware/`, `keys/` and APKs are git-ignored and must never be published.

## Credits
[ThomasPrior/LenovoSmartClock2](https://github.com/ThomasPrior/LenovoSmartClock2) and the XDA "Lets get started on the Lenovo Smart Clock 2" thread (pogo pinout, BROM access), [bkerler/mtkclient](https://github.com/bkerler/mtkclient), [fritzbytes/MicAnchor](https://github.com/fritzbytes/MicAnchor) (the mic-anchor idea), [jxlarrea/kiosk-satellite](https://github.com/jxlarrea/kiosk-satellite) and its [LineageOS Echo images](https://github.com/jxlarrea/lineageos-echo-kiosk-satellite) (inspiration), TymEK49 (first dock-light notes), AOSP avbtool.

## License
[MIT](LICENSE) for the code and documentation in this repository. Third-party APKs and Lenovo firmware are not included and keep their own licenses.
