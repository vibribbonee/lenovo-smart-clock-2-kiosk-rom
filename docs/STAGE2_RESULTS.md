# Stage 2 — validation on stock firmware (2026-11-23/24)

## Results
| Test | Result | Consequence for the ROM |
|---|---|---|
| CPU headroom | ✅ With clean audio the whole system idles at ≈7.5 % while listening (Kiosk Satellite 19 % of one core). Earlier 35–40 % was caused by corrupted mic audio. | Hardware is adequate. |
| Voice / wake word | ❌ stock: 0 detections (mic delivers ~6× mislabelled audio) → ✅ with MicAnchor opening 48 kHz stereo first: "fully consistent". | **Mic anchor is mandatory** and must hold the mic before Kiosk Satellite starts after boot (fails on reboot today: Kiosk Satellite wins the race by ~5 s). |
| Disable Cast (`mediashell`) | ✅ allowed; no visible breakage so far; frees ~200 MB RAM, ~9 % CPU, removes a 16 kHz mic client | Leave out of ROM. |
| Disable Google OTA updater | ✅ allowed | Leave out of ROM. |
| Disable Assistant (`assistant.core`) | ❌ refused: "Cannot disable a protected package" (it is Device Owner) | Can only be removed in the ROM image; after a reset into the ROM there is no Device Owner. |
| ADB over Wi-Fi | ✅ `adb tcpip 5555` works on stock; key auth enforced (`ro.adb.secure=1`). macOS: restart the adb server if `connect` says "No route to host". | HA can use authenticated ADB; better, DockBridge exposes the dock without ADB. |
| Dock light | ✅ set 0–10 via `charge_base` 7, read back via status byte 4 | DockBridge: `setLight`/`getLight`. |
| Phone on pad / charging | ✅ status byte 6: 1 empty, 2 present-not-charging, 3 charging, 9/0 transients | DockBridge: `phonePresent`, `phoneCharging`. No power/speed data exists. |
| Navigation bar prototype | ⏭ deferred: build our own KioskNav as the first app of the build pipeline instead of testing an unmaintained third-party app | — |

## Current changes on the device (all reversible)
| Change | Undo |
|---|---|
| `com.google.android.apps.mediashell` disabled | `adb shell pm enable com.google.android.apps.mediashell` |
| `com.google.assistant.display.android.updater` disabled | `adb shell pm enable com.google.assistant.display.android.updater` |
| ~~MicAnchor~~ (installed for testing, **removed** 2026-11-24, replaced by ClockBridge) | — |
| ClockBridge (`org.kioskclock.bridge`) installed; RECORD_AUDIO and WRITE_SECURE_SETTINGS granted | `adb uninstall org.kioskclock.bridge` |
| KioskNav (`org.kioskclock.nav`) installed and enabled as accessibility service | `adb uninstall org.kioskclock.nav` |
| ADB listening on TCP 5555 | reboot (or `adb usb`) |

After any reboot on stock, voice needs: `adb shell 'am force-stop me.jxl.kiosk_satellite; am force-stop org.kioskclock.bridge; am broadcast -n org.kioskclock.bridge/.BootReceiver'`.

## Own apps tested on stock (2026-11-24)
Built with `scripts/build/build_apps.sh` (no Gradle; signed with the project key in `keys/`, which must be backed up).
| App | Result |
|---|---|
| **KioskNav** 0.1.0 | ✅ Edge strip active; swipe up shows the bar; **Back, Home and long-press Settings all work** (user-confirmed). Works without SystemUI. |
| **ClockBridge** 0.1.0 — mic anchor | ✅ Holds 48 kHz x2; Kiosk Satellite reports 99 % of 16000 Hz. Replaces MicAnchor. |
| ClockBridge — first-boot provisioning | ✅ Enabled KioskNav's accessibility service by itself (with WRITE_SECURE_SETTINGS granted via adb; in the ROM via privapp-permissions). |
| ClockBridge — HTTP API (port 8765, bearer token) | ✅ Responds; rejects requests without token. |
| ClockBridge — dock via `charge_base` | ❌ on stock as a normal app: SELinux denies `untrusted_app` → `charge_base_service:service_manager find`. The firmware policy allows `priv_app` and `platform_app` (`system_api_service` attribute), so it **must be a priv-app in /product/priv-app** — already the ROM plan. |
| ClockBridge as boot anchor on stock | Not reboot-safe on stock (normal app starts after BOOT_COMPLETED, after Kiosk Satellite). In the ROM it is `persistent` and starts before HOME. |

## Not yet tested
Lenovo MDM agent disable (OTA code inside), Bluetooth audio with Cast off, factory reset behaviour (deliberately not tested on stock — Lenovo update server is dead), WebView 156 as provider (needs the ROM overlay).
