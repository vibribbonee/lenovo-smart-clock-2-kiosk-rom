# Test plan

Every test records: date, ROM build ID, result (PASS/FAIL/BLOCKED), evidence (log/screenshot/command output path). Failed tests are never removed from the record. Results go to `docs/test-results/<build>.md`.

Measurement commands: `dumpsys meminfo`, `cat /proc/meminfo`, `ps -A -o PID,RSS,NAME`, `logcat -b events | grep boot_progress`, `getprop sys.boot_completed`, `dumpsys gfxinfo <pkg>`, `dumpsys batterystats`‑free CPU via `top -n 1 -m 15`.

## Recovery (before relying on backups)
| ID | Test | Pass criteria |
|---|---|---|
| R1 | Enter BROM, `printgpt`, reset | GPT printed; device boots normally afterwards |
| R2 | Dump identity set twice | Identical SHA‑256 |
| R3 | Dump all partitions + full user area | Sizes match GPT; full‑image slices hash‑equal per‑partition files |
| R4 | Offline parse | `lpunpack` OK, `e2fsck -n` clean, boot/vbmeta parse, vbmeta digest matches running device |
| R5 | Write‑back of identical `logo` image | Re‑read hash identical; boots normally |
| R6 | (Stage 4) Restore stock boot+vbmeta+super after flashing ROM | Device boots stock |

## Baseline (stock, before changes)
| ID | Test |
|---|---|
| B1 | Cold boot time to `sys.boot_completed` and to kiosk visible (3 runs) |
| B2 | Idle RAM after 10 min (MemAvailable, PSS top 20) |
| B3 | Dock: light on/off/levels via current UI, Qi charging of a phone, `charge_base` getState/getStatus (ADB over Wi‑Fi) |

## Functional
| ID | Area | Test |
|---|---|---|
| F1 | Boot | Cold boot (power removed 30 s) ×5 |
| F2 | Boot | `reboot` ×5 |
| F3 | Boot | Power interruption during boot animation and during idle ×5 each; filesystem errors in `dmesg`/`logcat` |
| F4 | Kiosk | Starts automatically after boot without chooser |
| F5 | Kiosk | `am crash me.jxl.kiosk_satellite` / `kill -9` → back to kiosk within 5 s |
| F6 | Kiosk | Repeated crashes (5 in 1 min) — no "app keeps stopping" dead end; nav still reaches Settings |
| F7 | WebView | Provider = Google WebView (`dumpsys webviewupdate`); HA dashboard renders; CSS feature page; WebGL; video |
| F8 | WebView | Cookies / localStorage / IndexedDB persist across reboot; LAN HTTP and HTTPS (private CA) |
| K1 | Keyboard | Native text field, WebView input, textarea, password field (no learning), numeric |
| K2 | Keyboard | Dismiss via key and via nav Back; layout fits 800×480 |
| N1 | Nav | Swipe up from bottom shows bar; Back/Home/Settings work in kiosk, WebView, Settings |
| N2 | Nav | Nav works while kiosk is crashed/not running |
| N3 | Nav | Edge strip doesn't break dashboard swipes |
| W1 | Wi‑Fi | Connect via Settings; WPA2/WPA3 (if supported) |
| W2 | Wi‑Fi | AP reboot → automatic reconnection < 60 s; kiosk recovers page |
| BT1 | Bluetooth | Pair a device; on/off |
| S1 | Settings | Reachable from nav; Wi‑Fi, display, sound, date/time, developer options |
| T1 | Touch | Multi‑touch, edges/corners, no ghost touches over 24 h |
| A1 | Audio | Playback; volume keys change volume; mic capture 48 kHz stereo clean, 16 kHz mono (vendor bug — document/mitigate) |
| D1 | Dock | Light on/off, brightness 0–10, persists across undock/redock & reboot |
| D2 | Dock | Phone placed/removed → charging state reported correctly; distinguish phone‑present vs charging if hardware allows |
| D3 | Dock | Undock/redock while kiosk running; no crash in system_server |
| P1 | Perf | Idle RAM (target: ≥ 350 MB MemAvailable with kiosk running; baseline 377 MB *with* Google stack) |
| P2 | Perf | Idle CPU < 5 % after 10 min; no wakelock leaks |
| P3 | Perf | 72 h sustained run: no OOM kill of kiosk, memory trend flat, no thermal throttling events |
| FR1 | Reset | Factory reset from Settings → boots ROM, no Google setup, kiosk starts after Provision |
| FR2 | Reset | After reset: WebView provider, keyboard default, nav service enabled, dock bridge works, Wi‑Fi setup reachable |
| FR3 | Reset | Recovery‑menu wipe (Vol+) gives the same result |
| U1 | Updates | Install newer WebView / Kiosk Satellite over system copy; survives reboot; reset reverts to ROM version cleanly |
