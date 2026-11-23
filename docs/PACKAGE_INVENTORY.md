# Package inventory

Source: `pm list packages -f -u` (109 packages: 98 system, 11 user) + `dumpsys meminfo`, read 2026‑11‑23. Raw lists in `investigation/device/packages_*.txt`.

Classes: **1** Essential · **2** Hardware/OEM · **3** Android functionality · **4** Optional (disable first) · **5** Removable (verified unnecessary — *none yet*; promotion requires a passed test) · **6** Unknown (retain)

Policy: Stage 2 only *disables* (`pm disable-user`) class‑4 items, one group at a time, with the test plan between groups. Nothing moves to class 5 until it has been disabled through a full test cycle including a reboot and dock test.

## Framework / core
| Package | Location | Class | Notes |
|---|---|---|---|
| `android` (framework-res) | system/framework | 1 | Contains Lenovo `ChargeBaseManagerService` (dock) in services.jar |
| `com.mediatek` (mediatek-res) | system/framework | 1 | |
| `com.android.providers.settings` | priv-app MtkSettingsProvider | 1 | |
| `com.android.settings` | product/priv-app MtkSettings | 1 | Only Wi‑Fi/BT UI; also provides `FallbackHome` |
| `com.android.packageinstaller` | priv-app | 1 | |
| `com.android.permissioncontroller` | priv-app | 1 | |
| `com.android.shell` | priv-app | 1 | ADB shell |
| `android.ext.services`, `android.ext.shared` | | 1 | |
| `com.android.networkstack.inprocess`, `…permissionconfig` | | 1 | Network stack |
| `com.android.inputdevices` | | 1 | Keyboard layouts |
| `com.android.webview` 74.0.3729.186 | product/app | 1 | Only valid WebView provider today; keep as fallback |
| `com.android.providers.media`, `com.android.externalstorage`, `com.android.documentsui`, `com.android.mtp` | | 3 | Storage/file picker (file upload in WebView needs DocumentsUI) |
| `com.android.providers.downloads` (+ `.ui`) | | 3 | DownloadManager |
| `com.android.keychain`, `com.android.certinstaller` | | 3 | TLS certs (local HA with private CA) |
| `com.android.captiveportallogin`, `com.android.hotspot2` (OsuLogin) | | 3 | Wi‑Fi captive portal |
| `com.android.proxyhandler`, `com.android.pacprocessor` | | 3 | Proxy |
| `com.android.vpndialogs` | | 3 | |
| `com.android.location.fused` | | 3 | |
| `com.android.bluetooth` (MtkBluetooth) | system/app | 1 | BT framework |
| `com.android.providers.userdictionary` | | 3 | Keyboards |
| `com.android.localtransport`, `com.android.backupconfirm`, `com.android.sharedstoragebackup`, `com.android.wallpaperbackup` | | 3/4 | Backup framework; localtransport 3.7 MB PSS |
| `com.android.statementservice` | | 3 | App links |
| `com.android.provision` | product/priv-app | **1 (for our ROM)** | AOSP first‑boot provisioning; sets `device_provisioned`/`user_setup_complete` |
| `com.android.se` (SecureElement) | | 4 | No SE hardware expected; 3.7 MB |
| `com.android.dynsystem` | | 4 | DSU — useful for testing GSIs later; keep |
| `com.android.managedprovisioning` | | 3 | Needed if Kiosk Satellite becomes Device Owner via provisioning |
| `com.android.cts.ctsshim`, `com.android.cts.priv.ctsshim` | | 3 | Placeholders for APEX/updates; keep |
| `com.android.modulemetadata` | | 3 | |
| `android.auto_generated_rro_product__`, `…_vendor__`, `com.mediatek.frameworkresoverlay` | overlays | 1 | Device config overlays |

## Hardware / OEM (class 2 — retain)
| Package | Notes |
|---|---|
| `com.google.assistant.oemapp` ("Scoria OEM", **system uid**) | Volume‑key handling, locale, OTA, **dock night‑light / accessory service** (calls `charge_base` `setPower` on media start/stop). Keep until a replacement dock helper is proven. |
| `com.tonly.helios.ota` (AsseccoryOTA) | Dock MCU / Qi firmware updater (Tonly = dock ODM). Keep; consider disabling only after dock tests, because a dock firmware update interrupted by our changes could brick the dock. |
| `com.mediatek.capctrl.service` (MtkCapCtrl) | MTK capability control binder `capctrl` |
| `com.mediatek.duraspeed` | MTK background-app limiter; evaluate (may kill kiosk helpers) — class 6 |
| `com.mediatek.engineermode` | Engineering menu (radio uid). 3.7 MB. Class 4 but useful for diagnostics |
| `com.mediatek.ygps`, `com.mediatek.gnss.nonframeworklbs`, `com.mediatek.location.lppe.main` | No GNSS hardware expected → 4 |
| `com.mediatek.callrecorder`, `com.mediatek.autodialer`, `com.mediatek.simprocessor`, `com.mediatek.telephony` | Telephony bits → 4 (but see telephony note) |
| `com.mediatek.bluetooth` (CarBTDemo) | Demo app → 4 |
| `com.mesh.test.provisioner` | BT mesh test → 4 |
| `com.test.mtptest` (MTPDemo, **system uid**, running) | Unknown OEM test app → 6 until investigated |
| `com.lenovo.mdm.admin.agent` (**system uid**, 29 MB PSS, running) | Lenovo MDM/telemetry + OTA downloads. Class 6 → strong candidate for 4 after test |

## Telephony (device has no modem; class 4 but framework‑coupled)
`com.android.phone` (12.7 MB PSS), `com.android.server.telecom`, `com.android.providers.telephony`, `com.android.mms.service`, `com.android.carrierconfig`, `com.android.ons`, `com.android.cellbroadcastreceiver`, `com.android.simappdialog`, `com.android.providers.blockednumber`, `com.android.calllogbackup`, `com.android.emergency`.
`com.android.phone` and telecom are hard dependencies of the framework on many builds; **do not disable** — keep in class 6. The others are class 4.

## Google smart‑display stack (class 4 — the main debloat target)
| Package | PSS | Depends / risk |
|---|---|---|
| `com.google.android.apps.mediashell` (Cast) | ≈250 MB over 5 procs | Independent; removal loses Cast‑to‑clock |
| `com.google.assistant.core` | 15.6 MB | **Device Owner**, protected package (cannot be disabled via ADB on stock), provides edge‑swipe UI and current hotword mic capture. Removing it: loses default volume key UI (XDA) and must not leave DO dangling → only after factory reset into our ROM. |
| `com.google.assistant.launcher` | — | HOME candidate |
| `com.google.assistant.display.android.updater` | 4.5 MB | **OTA updater — disable early** to prevent an OTA overwriting work |
| `com.google.android.tts` | — | TTS; keep only if Kiosk Satellite uses system TTS (check) |
| `com.google.android.marvin.talkback` | — | Accessibility; class 4 |

## Optional AOSP/MTK apps (class 4)
`com.android.browser` (MtkBrowser), `com.android.calendar` (running, 4 MB), `com.android.providers.calendar` (6 — Kiosk may read calendar? check), `com.android.contacts`, `com.android.providers.contacts` (6 — many apps assume it), `com.android.music`, `com.android.musicfx` (3 — audio effects framework), `com.android.wallpaperpicker`, `com.android.dreams.basic`, `com.android.egg`, `com.android.protips`, `com.android.htmlviewer` (3 — license viewer), `com.android.bookmarkprovider`, `com.android.bips`, `com.android.printspooler` (running 3.8 MB), `com.android.printservice.recommendation`, `com.android.companiondevicemanager`, `com.android.bluetoothmidiservice`, `com.android.traceur`, `com.android.onetimeinitializer`, `com.android.nfc` (already disabled; no NFC).

## Current user apps (decide per app)
| Package | Recommendation |
|---|---|
| `me.jxl.kiosk_satellite` | Bake into ROM (`/product/app`) |
| `rkr.simplekeyboard.inputmethod` | Bake into ROM |
| `com.google.android.webview` 156 | Bake into ROM + make valid provider |
| `com.thinkdifferent.anywebview` | Not needed once RRO lists the provider |
| `io.github.sds100.keymapper` | Only if you need custom key mapping (volume keys); otherwise replaced by nav helper |
| `l.l` | Remove (two HOME apps cause chooser prompts) |
| `org.fdroid.fdroid` (and local `F-Droid.apk`) | Optional; not in the ROM by default |
| `org.mozilla.fennec_fdroid`, `app.smarttube.fdroid`, `com.topjohnwu.magisk`, `pl.mateusz.clockadbprobe` | Not in ROM |

## Memory snapshot (idle, Settings in foreground)
Total RAM 1,005,036 K · Used 872,543 K · Free 247,406 K · zram 98 MB physical for 269 MB swapped.
Largest: Kiosk Satellite 229.6 MB, mediashell 248 MB (all processes), settings 60 MB, system_server 53 MB, lenovo MDM 28.8 MB, assistant.core 15.6 MB, keymapper 15 MB.
