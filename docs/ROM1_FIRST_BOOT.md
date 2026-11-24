# ROM build 1 — first boot results (2026-11-24)

Flashed with `scripts/flash/mtk_session.sh flash` (log `backups/flash-logs/20261124-013102-flash`): data erased, seccfg unlocked, super + vbmeta_system written at the GPT offsets. Boots in **orange** state.

## Works
- Boot with our signed vbmeta_system (dm-verity on our hashes); `sys.boot_completed=1`.
- **Google WebView 156** is the active provider (AOSP 74 kept as fallback).
- ClockBridge starts early as a persistent priv-app (pid 984); its provisioning enabled KioskNav.
- KioskNav swipe-up bar; Settings reachable.
- USB debugging: after enabling it in Developer options the Mac was authorized without a dialog.
- Volume buttons reach ClockBridge (`com.tonly.KEYCODE_VOLUME_*` broadcasts) and change STREAM_MUSIC.

## Problems found → fixes for build 2
| Problem | Cause | Fix |
|---|---|---|
| Screen stayed on the boot logo; no home app | Kiosk Satellite's `HomeAlias` is `android:enabled="false"` in its manifest; it enables it itself when chosen as launcher. Shell can't change a system app's component state. | ClockBridge provisioning: enable `me.jxl.kiosk_satellite/.HomeAlias` (needs privileged `CHANGE_COMPONENT_ENABLED_STATE` → add to privapp XML). Workaround now: open Kiosk Satellite, choose "use as launcher". |
| No keyboard in text fields | `enabled_input_methods` empty after wipe | Provisioning: enable + set Simple Keyboard (done manually via `ime enable/set` for now). |
| Volume seems not to change | Lenovo's media volume range is **0–100**; `ADJUST_RAISE` steps by 1 (inaudible); no SystemUI volume UI | Step ~6–8 per press via `setStreamVolume`; KioskNav shows a small volume bar. |
| Brightness / auto-brightness don't work | Not yet diagnosed. Framework state: `screen_brightness=102`, mode manual, auto off, `mScreenBrightnessSetting=0`. On stock no `lights` service was registered either; brightness was likely driven by the Google apps. | Investigate the brightness path (light HAL / sysfs backlight / `mtkpower`) and light sensor (handle 5). |
| Stock apps visible (Browser, Calendar, CarBTDemo, Contacts, Music, …) | Always installed; hidden by the old launcher | Provisioning disables them; remove from image after a test round. |
| Mic mute switch | Not tested yet | Flip once with logcat running (`AndroidOemManager` "mic mute status"). |

Manual state on the device right now: Simple Keyboard enabled/default; Kiosk Satellite started manually (not yet home).
