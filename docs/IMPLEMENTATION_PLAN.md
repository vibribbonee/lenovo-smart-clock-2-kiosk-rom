# Implementation plan

## Guiding decisions
1. **Modified stock ROM, minimal footprint.** Keep stock `boot`, `vendor`, kernel, LK, preloader and all identity partitions byte‑identical. Changes go into `product` (and, only where unavoidable, `system`) inside `super`.
2. **Additive first, subtractive later.** Add overlays/apps/permissions; disable before delete; delete only after a passed test cycle.
3. **Everything appliance‑critical lives in the image**, so a factory reset (which wipes `/data`) restores a working kiosk.
4. **Every apk/overlay/prop is declared in `rom/manifest.yaml`** with SHA‑256; the build script regenerates the image from `firmware/original/` + manifest.

## Target architecture

```
super
├─ system   (stock; untouched in v1. Later: remove proven-unneeded apps)
├─ vendor   (stock; untouched)
└─ product  (modified)
   ├─ app/KioskSatellite/            the signed APK, unmodified (see note)
   ├─ app/GoogleWebView/             pinned Google-signed WebView (156.x first)
   ├─ app/SimpleKeyboard/            F-Droid-signed Simple Keyboard
   ├─ priv-app/KioskNav/             our edge-swipe nav bar (accessibility service)
   ├─ priv-app/DockBridge/           our charge_base bridge (whitelisted calls only)
   ├─ priv-app/KioskProvisioner/     first-boot settings (may merge into KioskNav)
   ├─ priv-app/MicAnchor (or folded into DockBridge)  holds mic at 48 kHz stereo from early boot — REQUIRED for voice (proven 2026-11-23)
   ├─ overlay/KioskFrameworkOverlay.apk   config_webview_packages (+ other framework config)
   ├─ overlay/KioskSettingsProviderOverlay.apk  defaults: screen timeout, stay-awake on power, etc.
   ├─ etc/permissions/privapp-permissions-kiosk.xml
   ├─ etc/default-permissions/default-permissions-kiosk.xml   runtime grants for Kiosk Satellite
   ├─ etc/sysconfig/kiosk-whitelist.xml   power-save whitelist for Kiosk Satellite/DockBridge
   └─ build.prop additions (documented)
   removed in v1: priv-app/com.google.assistant.core, priv-app/com.google.android.apps.mediashell,
                  priv-app/com.google.android.updater, app/com.google.assistant.launcher
                  (only after Stage 2 disable-tests pass)
vbmeta / vbmeta_system: stock images re-flashed with verification disabled flags (bootloader unlocked)
```

Note on Kiosk Satellite: the 247 MB universal APK must stay byte‑identical (re‑zipping breaks its signature and future self‑updates). Its native libs are stored uncompressed, so as a system app it runs them in place from the APK (no extraction). Storage cost ≈247 MB in `product`; ask the developer for an armeabi‑v7a split (≈90 MB) to save space.

### HOME and crash recovery
Kiosk Satellite (`.HomeAlias`) becomes the **only** HOME activity besides Settings' `FallbackHome` (priority −1000), so Android selects it automatically after reset, never shows a chooser, and relaunches it whenever it dies and the user/Back lands on Home. `BootReceiver` also starts it. No custom launcher needed. Device Owner + lock task is optional (Stage 6) and only if you want to prevent exit; it is not needed for crash recovery.

### First boot after reset
Stock first boot = Google Home setup driven by assistant.core (and a dead Lenovo update server). In our ROM assistant.core is absent, so AOSP `com.android.provision` (present) marks the device provisioned. `KioskProvisioner` (priv‑app, `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED`, idempotent, runs once per `/data`):
- enable KioskNav accessibility service; set default IME; grant Kiosk Satellite's special access where allowed (notification listener, `WRITE_SETTINGS`, overlay) via `appops`/secure settings — only what Kiosk Satellite actually needs;
- `stay_on_while_plugged_in`, screen timeout, `install_non_market_apps` as you choose;
- **no** disabling of permission checks, no `ro.secure=0`, no SELinux changes, ADB off by default (configurable at build time).
- Wi‑Fi is configured by the user in Settings (reachable from the nav bar) or in Kiosk Satellite.

### Debloat
v1 removes only the Google smart‑display stack (largest RAM win ≈ 270 MB PSS) after testing; MTK/Lenovo/telephony items are disabled via the provisioner (reversible) rather than deleted. Lenovo MDM agent is disabled after verifying it isn't a dependency of the dock or OTA services.

## Alternatives considered
- **A. Root + Magisk modules (upstream approach).** Fast, but modules live in `/data/adb` → **lost on factory reset**; Magisk ≥30 reportedly mis‑patches this boot image. Useful only as a *lab tool* in Stage 3.
- **B. Android 12 Go GSI (phh).** Reported working on XDA (with mic issue). Gives real SystemUI and gesture nav, newer platform. But loses Lenovo `ChargeBaseManagerService` (it's in the stock framework, not vendor) → dock would need a re‑implementation of the ttyACM0 protocol; Treble vendor quirks (audio, display, Wi‑Fi) are unverified; 1 GB RAM with Android 12. Keep as a research option, testable non‑destructively later via DSU (`com.android.dynsystem` is present) once unlocked.
- **D. Port jxlarrea's LineageOS 18.1 Kiosk Satellite Edition to MT8167.** It's the best existing kiosk ROM for this class of device (same author as Kiosk Satellite, OTA updates, HA firmware entity, memory tuning, no setup wizard, device owner on first boot). But its MT8163 base comes from R0rt1z2's Amazon port with a *buildable kernel*; for MT8167 there is no LineageOS device tree. We'd need a kernel from Lenovo's open‑source release (the XDA thread mentions the Lenovo open‑source portal; availability for CD‑24502F unverified), a new device tree, a vendor adaptation and our own dock HAL glue. That's months of work with uncertain results. **Not the first plan, but we borrow its ideas:** first‑boot install with grants and device owner (`ks-install.sh`), SettingsProvider defaults overlay, cached‑process limit (needs a framework patch → not possible on stock; approximate with `ro.lmk`/`max_cached_processes` props), and the OTA/update entity concept.
- **C. No unlock, ADB‑only provisioning.** Possible for debloat‑by‑disable and app installs, but cannot change the WebView provider list or survive factory reset. Good as Stage 2 (validation) only.

## Stages and approval gates

| Stage | What | Touches device? | Approval needed |
|---|---|---|---|
| **0** | Investigation & docs (this delivery) | read‑only ADB | — (done) |
| **1** | Full backup via BROM (read only): printgpt, rl (all), rf full, boot1/boot2, verify, second identity read, offline lpunpack | Enters BROM (power‑cycle with Vol+), reads only | **Yes** |
| **1b** | Prove restore path: write back an identical, non‑critical partition (e.g. `logo` with its own dump) and re‑read hash | Writes identical bytes to one partition | **Yes** |
| **2** | Validation on stock (no unlock): **measure CPU headroom first** (kiosk idle/listening, wake latency, jank — the go/no‑go for the whole project); disable OTA updater (approved 2026‑11‑23); then disable Google stack groups one by one (`pm disable-user` where not protected), measure RAM/boot, test dock + kiosk + keyboard + nav PoC (VirtualSoftKeys or KioskNav debug build) | Reversible package state | **Yes** (per group) |
| **3** | Build pipeline on Mac: lpunpack → unshare/resize ext4 → add product files → lpmake → super.img; build overlays/helper apps; `rom/manifest.yaml`; image diff report | none | — |
| **4** | Unlock bootloader (seccfg) — **wipes userdata** — flash `vbmeta*` with verification disabled + new `super` | Irreversible‑ish (re‑lock possible with backup) | **Yes, with risk briefing** |
| **5** | Full test plan incl. factory reset, sustained 72 h run | Normal use | Per destructive test (reset) |
| **6** | Hardening & polish: remove proven‑unused packages, optional Device Owner/lock task, boot animation, ADB policy | Reflash | **Yes** |

## Repository layout (created)
```
README.md            architecture & workflow
docs/                this documentation
scripts/collect/     read-only device data collection
scripts/backup/      BROM backup + verification (not run yet)
scripts/build/       ROM build (Stage 3, not written yet)
rom/                 manifest + overlay/helper sources (Stage 3)
firmware/original/   untouched extracted images (git-ignored)
firmware/work/       build outputs (git-ignored)
backups/             raw device dumps (git-ignored, read-only, never uploaded)
investigation/       raw evidence from the device (git-ignored: contains serial)
upstream/            cloned reference repo
```
