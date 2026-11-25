# TODO (updated 2026-11-25, build 7: ClockCore 1.0 / ClockBridge 1.0.0 / KioskNav 1.0.0)

## Open: verify on the clock
- [x] Slam: tap on top snoozes a ringing alarm (with an "Alarm snoozed" banner); Vol ± change the ringing alarm's volume (tested 63 → 42 %).
- [ ] Double tap stops a ringing alarm (needs two taps within 0.45 s; not tried yet).
- [ ] Wake-up light: Kiosk alarms show up as Android's next alarm clock (`dumpsys alarm`); dock fade and an HA light fade on a real alarm.
- [ ] Gestures from the on-clock settings screen and from HA (`/api/gesture`, the *Run … action* buttons).
- [ ] Home Assistant integration end to end: discovered via mDNS, *Show token*, entities, pushed events, device triggers.
- [ ] Dock light + phone charging through HA (clock back on the dock).
- [ ] Voice after a cold boot (mic-switch boot fix is in build 3: seen working on the first boot).
- [ ] Kiosk Satellite auto-update installs silently (needs a new Kiosk release to happen).
- [ ] Wi-Fi reconnect, Bluetooth audio, 72 h stability, idle RAM/CPU.
- [ ] **Factory reset** from Settings and recovery: everything comes back without manual steps. Check the leftover Device Owner (`com.google.assistant.core`, no longer in the image) doesn't get in the way.

## Morning checklist (build 7 → v1.0.0)
1. **Flash build 7**: `./clockrom.sh flash` → `update` → after the *"Now: …"* line, hold both volume keys and plug power in. (Not done overnight on purpose: it changes how the apps start at boot.)
2. **Restart Home Assistant** once (the latest integration is already downloaded into HA).
3. On the clock, check: it boots; the tour shows; *"OK Nabu"* works (`adb shell settings get global kioskclock_mic_anchor_state` = *holding 48000 Hz x2*); swipe up shows ♫ while your phone plays over Bluetooth; the Bluetooth tile says *Ready to pair: Smart Clock 2*.
4. **Pair HA**: *Discovered* → *Pair on the clock* → Allow. Then entities, a tap event, the *Run … action* buttons, the *Kiosk Clock apps* update entity (*up to date*).
5. **Alarms**: set a Kiosk alarm 25 min ahead with *Wake-up light: Both* (pick a lamp under *Kiosk Clock → Configure*): dock + lamp fade; tap = snooze, double tap = stop; a 1-minute timer stopped by a tap; taps with nothing ringing don't snooze.
6. **SmartTube**: Clock settings → Extras → Install; open it; try touch; *Remote control* → link from the YouTube app.
7. **Kiosk defaults** (with adb): test the `ks.provision` payload format (mic gain, Clock screensaver) — see below.
8. **Publish v1.0.0** (draft is ready with all four APKs), add the repo topics (command below), then check the clock reports *up to date*. Later a tiny 1.0.1 proves the over-Wi-Fi update.
9. **Factory reset** once: setup returns by itself (incl. ClockCore's mic permission, the Bluetooth name).

## Open: next up
- [ ] Kiosk Satellite defaults through its `ProvisionActivity` (`ks.provision`, needs `DUMP`, which a priv-app can hold): microphone gain (~+17 dB), Clock screensaver with Night mode. Payload format undocumented: test on the clock over adb with a harmless payload first.
- [ ] On the clock (build 7): media controls with Bluetooth audio, the Bluetooth pairing tile and the *Smart Clock 2* name, SmartTube install + casting + how usable touch is.
- [ ] Experiment: the clock as a Bluetooth → Music Assistant **line-in** (Sendspin `source@v1` role): capture the A2DP sink audio (privileged CAPTURE_AUDIO_OUTPUT / remote submix) and offer it as a Live Input, so a phone paired to the clock plays on every speaker. Needs the Sendspin source protocol and a latency test.
- [ ] Build 7 on the clock: ClockCore holds the mic at boot, ClockBridge starts without being persistent, one real over-Wi-Fi update (publish v1.0.0 first), onboarding tour, timers with the tap.

## Backlog
- [ ] Flashing without `sudo` (mtkclient + libusb permissions on macOS/Linux) and Windows support.
- [ ] WebView: a stable official download source would allow auto-fetch (APKMirror blocks scripted downloads); keep the manual file option.
- [ ] Remove the 5 still-visible disabled system apps from the image (needs re-hashing system; see the discussion in git history).
- [ ] CI: GitHub Action running the HA tests, hassfest and HACS validation on every push.
- [ ] HA: translations (contributions welcome); integration icon via home-assistant/brands.
- [ ] Onboarding: a QR code for the Kiosk Satellite Remote page.
- Not possible: flashing through the dock (its USB-A port is power only).

## Open: decisions
- [ ] **Casting:** gone with the Cast receiver. Kiosk Satellite's media player (Sendspin / Music Assistant) is the realistic replacement.
- [ ] ADB in the release build: off by default, on, or Wi-Fi with key.

## Open: housekeeping
- [ ] Post the mic bug report to Kiosk Satellite (`docs/reports/kiosk-satellite-mic-ladder.md`).
- [ ] Reproducible app builds (fixed zip timestamps).
- [ ] Later: try a GSI/LineageOS via DSU without touching our ROM.
- Not possible: shorter/no orange unlock warning (LK is signature-checked by the preloader).

## Known limitations
- Brightness nudges in Auto mode are recorded but not applied to the curve (`mUserLux=-1`); Kiosk Satellite's own brightness control covers this.
- Alarm volume: Kiosk Satellite sets the alarm stream to its own *Alarm volume* setting at every ring; change it there (Vol ± during a ring works for that ring).
- Kiosk Satellite already exposes screen, media volume, brightness and alarms to HA (its ESPHome device); the Kiosk Clock integration only adds what ClockBridge knows (dock, mic switch, gestures).

## Done
- **Out of the box (build 2):** Kiosk Satellite home app, Simple Keyboard, lock screen off, 24 h, time zone, timeout never, stay awake, logd 1 MiB, Kiosk's special access granted on boot.
- **Fixed:** volume steps + bar + click, auto-brightness, mic switch (state from Lenovo's OEM service, boot retry), tap detection (reliable without Assistant).
- **Build 3:** Kiosk Clock HA integration (HACS, mDNS discovery, token on the clock), configurable gestures (hold Vol±, tap, double tap) incl. alarm snooze/stop, Vol ± = alarm volume while ringing, HA examples in docs.
- **Trimmed:** Cast receiver, Assistant, Google/Lenovo updaters, 13 + 21 unused stock apps disabled or removed (provisioning v4; +39 MB RAM). Kept on purpose: `com.google.assistant.oemapp` (mic switch state), phone/telecom (framework), dynsystem (DSU).
- **Keys:** backed up in the private repo `vibribbonee/lenovo-smart-clock-2-keys` (SjorsMaster has access). One set per builder, not per clock.
- **Build 2 history:** first flash attempt dropped USB at 4 % (nothing harmful written), retry succeeded. Wake word after a wipe needed Kiosk's mic gain raised (~17 dB).
