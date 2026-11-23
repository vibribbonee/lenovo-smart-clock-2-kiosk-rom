# Firmware — current state of this device

All values read via ADB (`getprop`, `dumpsys`, `pm`) on 2026‑11‑23 without modifying the device.

## Build
| Property | Value |
|---|---|
| Android | **10** (API 29), `ro.build.id=QP1A.190711.020` |
| Display ID | `1.2.2_627_LenovoCD-24502F_ROW_2201050321 release-keys` |
| Fingerprint | `LENOVO/Helios/Helios:10/QP1A.190711.020/LenovoCD-24502F_ROW_1.2.2.627_220105:user/release-keys` |
| Product version | `ro.product.version=1.2.2.627` |
| Build date | 2022‑01‑05 (`user` build, `release-keys`, `ro.debuggable=0`, `ro.secure=1`) |
| Security patch | **2020‑10‑05** (system and vendor) |
| MediaTek BSP | `alps-mp-q0.mp3-V4.69` |
| Kernel | `4.14.141+ #1 SMP PREEMPT Wed Jan 5 03:00:27 UTC 2022`, armv7l, GCC 4.9 |
| Treble | `ro.treble.enabled=true`; `system` fstab entry accepts **GSI AVB keys** (`q-gsi`, `r-gsi`, `s-gsi`) |
| Dynamic partitions | `ro.boot.dynamic_partitions=true` → `system`, `vendor`, `product` are logical partitions in `super` |
| A/B | **No** (single slot; separate `recovery` partition) |
| Encryption | FBE (`ro.crypto.type=file`, `aes-256-xts` / `aes-256-cts`), metadata on `md_udc` |
| SELinux | enforcing (shell context `u:r:shell:s0`) |
| OTA channel | `persist.sys.current.channelname=stable-channel` |

## Boot / verified boot state
| Property | Value | Meaning |
|---|---|---|
| `ro.boot.flash.locked` | `1` | **Bootloader locked** |
| `ro.boot.vbmeta.device_state` | `locked` | |
| `ro.boot.verifiedbootstate` | `green` | Stock, verified boot passes |
| `ro.boot.veritymode` | `enforcing` | dm-verity on |
| `ro.boot.avb_version` | `1.1` | |
| `ro.oem_unlock_supported` | `1` | OEM unlock toggle exists |
| Root | **Not rooted.** `su` absent, `/data/adb` inaccessible. The Magisk *app* (31.1) is installed but no patched boot is active. |

## Google / OEM stack actually present
This is **not** a GMS device: there is no `com.google.android.gms`, `gsf`, or Play Store. The "Google" part is the smart‑display stack:
- `com.google.assistant.core` (v11.51.6) — Assistant UI, **Device Owner**, home shim, bottom/top edge gesture strips, mic hotword.
- `com.google.android.apps.mediashell` — Cast receiver (Chromium‑based, 5 processes).
- `com.google.assistant.oemapp` ("Scoria" OEM bridge, **system uid**) — volume keys, locale, OTA, **dock accessory (night light) via `charge_base`**.
- `com.google.assistant.display.android.updater` — Google/Lenovo OTA updater.
- `com.google.assistant.launcher`, `com.google.android.tts`, `com.google.android.marvin.talkback`.

There is **no SystemUI package and no AOSP launcher** in the image. See `NAVIGATION.md`.

## User‑installed state (lost on factory reset)
`me.jxl.kiosk_satellite` 2026.11.24 (current HOME), `rkr.simplekeyboard.inputmethod` 6.8 (current IME), `com.google.android.webview` 156.0.8078.50 (installed but **not a valid provider**), `com.thinkdifferent.anywebview` 1.4.5 (needs LSPosed/root — inactive), `io.github.sds100.keymapper` (accessibility enabled), `l.l` (ultra‑small launcher), `org.fdroid.fdroid`, `org.mozilla.fennec_fdroid`, `app.smarttube.fdroid`, `com.topjohnwu.magisk`, `pl.mateusz.clockadbprobe` (an ADB/permissions probe app — origin to confirm).

## Update infrastructure (risk)
- XDA reports (2026) that Lenovo's update server `smartdm.lenovo.com` no longer works; freshly reset units on old builds loop in "Updating… Network problem". **A stock factory reset of this unit could therefore leave it unable to finish Google‑Home setup.** Our ROM must not depend on that setup flow.
- An OTA URL on `smartdmdl-stage.lenovo.com` was posted on XDA; no complete factory image source is currently verified (the lolinet mirror cited on XDA returns 404 as of today). **Your own full dump is the only known restore image.**

## Stock tools for flashing
- **Fastboot** exists (`adb reboot bootloader`, or Vol+ menu); XDA users report `fastboot flashing unlock` working once OEM unlocking is enabled.
- **MediaTek BROM / mtkclient** works on MT8167 (Kamakiri2 payload; SBC+DAA enabled, SLA off). This is the low‑level recovery path. mtkclient 1.6.3 is installed on this Mac (`/opt/homebrew/bin/mtk`).
