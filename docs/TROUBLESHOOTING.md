# Troubleshooting and recovery

Find your symptom, try the fix. Nothing here can brick the clock: the download mode used for
flashing (hold **both volume keys** while plugging in power) lives in the chip's boot ROM, which no
flash can touch. Deeper background: [BACKUP_AND_RECOVERY.md](BACKUP_AND_RECOVERY.md), [FLASHING.md](FLASHING.md).

Commands with `adb` need the pogo USB cable and USB debugging (on in this ROM). Logs for a bug
report: `adb logcat -d | grep -E "ClockBridge|ClockCore|KioskNav" > clock-log.txt`.

## Flashing

| Symptom | Fix |
|---|---|
| `NOTHING WAS WRITTEN` / mtkclient says *"Please disconnect, start mtkclient and reconnect"* | The clock was already in download mode before mtkclient started. Unplug its power, wait 5 s, run `./clockrom.sh flash` again, and only plug power back in (holding both volume keys) after the *"Now: power off the clock…"* line. |
| mtkclient waits forever | Wrong order or the clock isn't in download mode: unplug power, keep the pogo USB attached, hold **both** volume keys, plug power in, keep holding ~3 s. Check the pogo cable is seated. Avoid USB hubs. |
| USB dropped in the middle of the flash | Harmless to retry: run the same command again before using the clock. If it was cut off while writing `super`, the clock may not boot until you do. |
| `INCOMPLETE: only 1 of 2 partitions were written` | Same: run `./clockrom.sh flash` again right away. |
| Asks for a password | macOS/Linux: mtkclient needs administrator rights for USB. It's your computer's password. |

## Booting

| Symptom | Fix |
|---|---|
| Orange *"unlocked"* warning at every boot | Normal after unlocking the bootloader; it waits ~5 s or a key press. It can't be removed (the boot loader is signature-checked). |
| First boot after a flash takes a few minutes on the Lenovo logo | Normal (Android optimises the apps). Give it 10 minutes. |
| Stuck on the logo for more than 10 minutes, or reboots in a loop | Flash the previous build again (`firmware/work` from that build, or rebuild it), or go back to stock: `./clockrom.sh restore`. See *If it does not boot* in [FLASHING.md](FLASHING.md). |
| Boots, but a black/white screen with no home screen | Kiosk Satellite is the home app. Press Home (swipe up from the bottom edge → Home). If nothing: `adb shell pm enable me.jxl.kiosk_satellite/.HomeAlias`, then reboot. |

## On the clock

| Symptom | Fix |
|---|---|
| Swiping from the top or bottom edge does nothing | The nav bar and quick panel are KioskNav, an accessibility service. `adb shell settings put secure enabled_accessibility_services org.kioskclock.nav/org.kioskclock.nav.NavService` and `adb shell settings put secure accessibility_enabled 1`. |
| No keyboard when typing (Wi-Fi password) | `adb shell ime set rkr.simplekeyboard.inputmethod/.latin.LatinIME` |
| Screen stays black | It may be the "screen off" black screen: tap it. A tap on the top doesn't wake it unless a gesture is set to *Screen on / off*. |
| The tour shows again / never shows | It shows once per fresh setup. Show it any time: quick panel → **Clock** → *Show the tour*. |

## Voice

| Symptom | Fix |
|---|---|
| *"OK Nabu"* never triggers (especially after a reset) | Raise Kiosk Satellite's microphone gain: in its settings (or the Remote page at `http://<clock-ip>:2324`) → **Microphone** → gain around **+15 to +20 dB**. The default is too quiet for this clock's mics. |
| Voice stopped working after a while / sounds distorted | Check the mic anchor: `adb shell settings get global kioskclock_mic_anchor_state` should say *holding 48000 Hz x2*. If it says *waiting for RECORD_AUDIO*, reboot once. Mic switch on the back set to mute? (red dot on screen). |
| Kiosk Satellite says it isn't connected to Home Assistant | Set it up from your phone: open `http://<clock-ip>:2324` (Kiosk Satellite Remote), enter your HA address and token there. The clock's IP is in quick panel → **Clock**. |

## Home Assistant

| Symptom | Fix |
|---|---|
| The clock isn't under *Discovered* | mDNS doesn't cross VLANs/guest Wi-Fi. *Add integration → Kiosk Clock* with the clock's IP (quick panel → **Clock**). |
| *Pair on the clock* shows no prompt on the clock | Swipe up → Home first (the prompt draws over Kiosk Satellite). Update the ROM if the clock runs ClockBridge before 0.9.3, or choose *Enter the API token*. |
| *"Another pairing request is still waiting"* | Answer the prompt on the clock (Deny is fine) or wait 2 minutes. |
| HA says the token stopped working | *Reconfigure* → *Pair again on the clock*. |
| Taps / button holds don't arrive in HA (sensors do) | HA needs to know its own address: *Settings → System → Network → Local network*. The integration registers that URL on the clock as its webhook. |
| Dock light / phone sensors *unavailable* | The clock isn't on the dock (they only work docked). |

## Alarms, timers and taps

| Symptom | Fix |
|---|---|
| Tapping doesn't snooze | Tap firmly on the top. Check the tap is seen: `adb logcat -d \| grep "event tap"`. Taps while ringing use *Tap while ringing* (quick panel → **Clock**). |
| A double tap counts as two single taps | Quick panel → **Clock** → *Double-tap speed* → *Relaxed*. |
| Alarm too loud or quiet | Kiosk Satellite sets the alarm volume itself: its **Alarms** settings → *Alarm volume*. Vol ± during a ring change that ring only. |
| Wake-up light didn't start | It fades for alarms that were set before their fade window (a snooze never fades). Check *Wake-up light* isn't *Off*; HA lights also need picking under *Kiosk Clock → Configure*. |

## Updates

| Symptom | Fix |
|---|---|
| Is it up to date? | HA: the *Kiosk Clock apps* update entity. On the clock: `curl http://<clock-ip>:8765/api/status` (with the token) → `apps_update_result`. |
| Turn automatic app updates off | `kioskclock_app_updates` = `0` (HA: `/api/settings`, or `adb shell settings put global kioskclock_app_updates 0`). |
| An update broke something | Re-flash the ROM build you had (`./clockrom.sh flash`); the flashed apps are replaced by a newer update only if its version is higher, so also set `kioskclock_app_updates` to `0` until fixed, and open an issue with the log. |

## Starting over

- **Factory reset** (keeps the ROM, wipes your settings): Android Settings (quick panel → hold the Settings tile) → System → Reset options → Erase all data. On the next boot the ROM sets itself up again (home app, keyboard, disabled apps, tour). You'll need to set up Kiosk Satellite again, including the microphone gain.
- **Back to stock firmware**: `./clockrom.sh restore` writes your own backup (`./clockrom.sh backup`, step one of the install) back. The bootloader stays unlocked (orange warning); see [BACKUP_AND_RECOVERY.md](BACKUP_AND_RECOVERY.md) for a full return.
- **No backup?** You need one from *your* clock (it holds the clock's serial and calibration data); there is no generic image to download.
