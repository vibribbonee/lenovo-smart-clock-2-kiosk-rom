# Dock (wireless‑charging base with night light)

## Architecture as found in the firmware [F]

```
 Dock MCU (Tonly)  ──USB CDC-ACM──►  /dev/ttyACM0
                                         │  (pogo USB; clock is USB host when docked)
                    /system/lib/hw/heliosbase.default.so   (legacy HAL module "HELIOS_BASE")
                       heliosbase_status, heliosbase_led_ctrl, heliosbase_led_power_ctrl,
                       heliosbase_power_ctrl, heliosbase_mcu_*, heliosbase_wireless_* (firmware update)
                                         │  JNI: libandroid_servers.so
                    system_server: com.android.server.ChargeBaseManagerService
                       binder service "charge_base"  (android.app.IChargeBaseManager, framework.jar)
                                         │
       com.google.assistant.oemapp (system uid): ScoriaAssistantOemAccessoryService
          "OEM app: turn off LED night light", chargeStateCheck, setPower on media startPlay/stopPlay
       com.tonly.helios.ota (AsseccoryOTA): dock firmware updates (/system/xbin/user.bin = MCU fw,
          /system/xbin/wireless.bin = Qi transmitter fw)
 Kernel input device "heliosbase-status": KEY 0xfa / 0xfb (dock attach/detach or charge events – TBD)
 DockObserver service: reported state 0 (undocked) at time of reading
```

Key conclusion: **the dock is controlled by a Lenovo service inside system_server (framework/services.jar), not by the Google apps.** The Google `oemapp` is only a client. Removing assistant/mediashell does not remove the dock driver; removing `oemapp` would remove the current *UI* for the night light.

## `IChargeBaseManager` transaction codes (decoded from this device's framework.jar static fields) [F]
| Code | Method | Signature | Safe? |
|---|---|---|---|
| 1 | setUpdate | (int) | ⚠ update flag — don't use |
| 2 | getMCUVersion | () → String | read |
| 3 | getFirewareVersion | () → String | read |
| 4 | checkSN | () → String | read |
| 5 | checkModel | () → String | read |
| 6 | **getStatus** | () → byte[] | read — dock status bytes (charging state likely here) |
| 7 | **setLedBri** | (byte) | **night‑light brightness** (community: 0–10) |
| 8 | setPower | (byte) | power control (oemapp toggles it on media play/stop — meaning TBD: amplifier/charger?) |
| 9 | mcuUpdate | (boolean force) → int | ⛔ **flashes dock MCU firmware — never call** |
| 10 | setLedAndPower | (byte led, byte power) | write |
| 11 | initNative | () → boolean | opens HAL |
| 12 | release | () | closes HAL |
| 13 | **getState** | () → int | read — base attached state |
| 14 | wirelessUpdate | (boolean force) → int | ⛔ **flashes Qi transmitter firmware — never call** |
| 15 | registerCallback | (String, IChargeBaseCallback) | callback: startPlay/stopPlay |
| 16 | unregisterCallback | (String, IChargeBaseCallback) | |

The community project TymEK49/Lenovo-Smart-Clock-2 publishes a different mapping (e.g. "8 = startPlay"); **its codes 8/13/15/16 are wrong for this build** — the table above is from the firmware itself.

Observed in logcat while undocked: `heliosbase: Can't get HELIOS_BASE module!!!` / `ChargeBaseService: setPower ret:-1` — the HAL only works with the dock attached.

## What's still unknown (needs dock attached + ADB over Wi‑Fi)
- Layout of `getStatus()` bytes: which bit = Qi transmitter active / phone present / phone charging / foreign object; whether "phone charging" is distinguishable from "phone present".
- Whether `charge_base` enforces a permission/UID check (TymEK49 used `adb shell service call`, so the shell UID is likely allowed; a normal app UID may not be).
- Meaning of `setPower` and whether `setLedBri` persists across undock/redock or reboot.
- What `heliosbase-status` key 0xfa/0xfb events mean.
- Clock's own power state: there is no battery; `dumpsys battery` reports nothing useful. "Clock power status" is therefore just "on mains" — the only charging state with meaning is the **phone on the Qi pad**.

## Current use
Home Assistant drives the dock today with ADB commands (`service call charge_base …`, presumably via the ADB integration as in TymEK49's guide). That needs ADB over Wi‑Fi, which on this stock build only works through smartclock2tool **with ADB authentication disabled** (see `SECURITY_NOTES.md`). The ROM should keep HA control working **without** unauthenticated ADB.

## Live test results (2026-11-24, clock on dock, ADB over Wi-Fi)
- `/dev/ttyACM0` appears when docked; it is **world read/write** (`crw-rw-rw- usb usb`), so any app (or the shell) can talk to the dock MCU directly. That's convenient for DockBridge but also a security note: any installed app could change the light or call the firmware-update commands.
- `charge_base` calls work from the **shell uid** with no permission check (codes used: 2, 3, 5, 6, 7, 13).
- Read results: MCU version `"22"`, Qi (wireless) firmware `"127"`, model `"0"`, getState (13) = `0`, DockObserver state 0 even while docked (DockObserver is not used for this dock).
- **getStatus (6) returns the raw 8-byte MCU frame** `80 51 08 L 01 C 00 S`:
  - `L` = current night-light level (0, 3, 6 read back exactly; setting 10 reads back 11)
  - `C` = Qi charger state, observed values: **1** pad idle, no phone · **2** phone detected / not drawing charge (seen on placement and once mid-charge) · **3** charging · **9** transient for about 1 s during charging (likely renegotiation or foreign-object check) · **0** for about 1 s right after the phone was removed (then back to 1)
  - `S` = checksum = sum of the preceding bytes (mod 256), verified
- **setLedBri (7)** `service call charge_base 7 i32 N` with N = 0…10 changes the light immediately (user confirmed visually).
- **Not available:** charging power/current, phone battery level, charge speed. Only state codes.
- Raw recordings: `investigation/dock_phone_test.txt`, `investigation/dock_phone_logcat.txt`.

Meaning of `C` values 2 and 9 is inferred from one phone and one session; confirm with a second phone and a "battery full" case before exposing them to Home Assistant as anything more specific than "phone present" / "charging".

## Recommended integration
**Small privileged helper app "Dock Bridge"** (`/product/priv-app/DockBridge/`, ~50 KB):
- Talks to `charge_base` via `ServiceManager.getService("charge_base")` + the AIDL interface (hidden API → declare `android:usesNonSdkApi="true"`, allowed for privileged system apps on Android 10, or use raw `IBinder.transact` with the decoded codes).
- **Whitelists** only codes 2–7, 10, 13 (and 8 after we understand it). Codes 1, 9, 14 are hard‑blocked in code.
- Exposes a tiny, permission‑protected API to Kiosk Satellite: bound service/broadcasts (`setLight(0..10)`, `getLight()`, `getDockState()`, `getPhoneCharging()`), plus a localhost‑only HTTP endpoint if Kiosk Satellite prefers (to be agreed with what Kiosk Satellite's plugin system supports — it already has a Shizuku plugin that could issue `service call` but Shizuku does not survive reboots without ADB).
- Listens for `heliosbase-status` changes via `IChargeBaseCallback`/polling and publishes state changes.
- Remembers the user's light level and re‑applies on dock attach.

Keep `oemapp` and `AsseccoryOTA` in the image (possibly disabled later) until Dock Bridge is proven; never delete `heliosbase.default.so`, `user.bin`, `wireless.bin`, or the ChargeBase framework code.
