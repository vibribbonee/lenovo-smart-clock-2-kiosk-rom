# Lenovo Smart Clock 2 — Kiosk Appliance ROM

Turns a Lenovo Smart Clock 2 (CD‑24502F, MediaTek MT8167S, Android 10, 1 GB RAM, 32‑bit) into a Google‑free, single‑purpose Home Assistant kiosk running **[Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite)**: modern WebView, working wake word, on‑screen navigation and quick settings, dock night light and phone‑charging state, and a setup that survives a factory reset.

> **Disclaimer.** Not affiliated with Lenovo, Google or MediaTek. This repository contains **only scripts, our own apps and documentation — no Lenovo firmware**. You build the ROM from a backup of *your own* clock. Unlocking and flashing can brick a device; the backup/restore path documented here is tested on one unit, not guaranteed for yours. Use at your own risk.

## Status
Built from stock firmware **1.2.2.627** and running daily on a real clock (KioskNav/ClockBridge 0.8.0). Tested on one unit. Still open: the factory-reset test and a few on-device checks — see [docs/TODO.md](docs/TODO.md).

## What you get
| | |
|---|---|
| **Kiosk Satellite** | the only home app, starts on boot, recovers after crashes, updated automatically from its GitHub releases (signature-checked) |
| **Modern WebView** | Google WebView you supply (tested: 157) replaces the 2019 WebView 74; updatable |
| **Voice that works** | ClockBridge holds the mic at 48 kHz stereo from early boot, working around a MediaTek MT8167 capture bug ([details](docs/PERFORMANCE_BASELINE.md)) |
| **Navigation** | swipe up from the bottom edge: Back · Home (the firmware has no SystemUI) |
| **Quick settings** | swipe down from the top edge: brightness + Auto, volume, dock light, Wi‑Fi, Bluetooth, Night light, screen off (tap to wake), restart, Settings (tap: clock settings, hold: Android Settings) |
| **Hardware** | volume keys with click; hold Vol+ / Vol− for a shortcut of your choice (voice assistant, play/pause, mute, dock light, night light, screen off, or just an HA event); mic‑switch indicator (several styles); tap‑on‑top and dock events |
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

## Quick start
You need: a Smart Clock 2 on firmware **1.2.2.627**, a pogo‑pin USB connection ([upstream guide](https://github.com/ThomasPrior/LenovoSmartClock2)), and a Mac or Linux PC (tested on macOS; Linux should work but is untested; Windows isn't supported). `./clockrom.sh doctor --install` installs the rest — e2fsprogs, adb, libusb and [mtkclient](https://github.com/bkerler/mtkclient) — asking before each step (mtkclient 1.6.3 was used on a real clock; the installer fetches the current release).

```bash
git clone https://github.com/vibribbonee/lenovo-smart-clock-2-kiosk-rom && cd lenovo-smart-clock-2-kiosk-rom
./clockrom.sh doctor --install   # checks/installs tools (asks first), fetches AOSP avbtool
./clockrom.sh backup      # read-only full backup of your clock (~15 min) + verification
./clockrom.sh build --webview ~/Downloads/<webview>.apk    # offline build from your backup
./clockrom.sh flash       # restore rehearsal, then first flash (unlock + wipe) or update (keeps data)
```
Nothing is written to the clock unless you type the confirmation word the script shows. `./clockrom.sh restore` puts your stock firmware back. Details and manual recovery: [docs/FLASHING.md](docs/FLASHING.md), [docs/BACKUP_AND_RECOVERY.md](docs/BACKUP_AND_RECOVERY.md).

`build` fetches the latest Kiosk Satellite (GitHub) and Simple Keyboard (F‑Droid) and checks every APK's package, signing certificate, 32‑bit ARM code and Android 10 compatibility before it goes in. With the Android SDK (platform 29, build‑tools 34, Java 21) our own apps are built from source; without it, the signed APKs from this repo's latest release are used.

### Why you bring your own firmware and WebView
This repository ships **no Lenovo or Google binaries**: they're copyrighted and not ours to redistribute, and a repo that hosts them gets taken down. So:
- **Firmware** comes from *your* clock: `./clockrom.sh backup` dumps it, and the ROM is built from that dump. The backup is also your way back to stock, so it's step one anyway. Keep it private — it holds your clock's serial, MAC addresses and calibration data.
- **Google WebView**: the clock's own WebView is Chromium 74 (2019), too old for current Home Assistant dashboards. Download Android System WebView from **[APKMirror](https://www.apkmirror.com/apk/google-inc/android-system-webview/)**: latest **stable** release (not Beta/Dev/Canary) → variant **armeabi‑v7a**, **nodpi**, minimum Android ≤ 10 → the plain **.apk** (not an `.apkm` bundle, not Trichrome). Pass it with `--webview FILE`. The script refuses a wrong variant or a file not signed by Google. Already have a newer WebView on the clock? `--webview-from-device` pulls it over adb. `--no-webview` keeps WebView 74.

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
