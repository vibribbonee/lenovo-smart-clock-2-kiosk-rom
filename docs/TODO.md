# TODO (updated 2026-11-24, build 3: ClockBridge 0.9.0 / KioskNav 0.8.3)

## Open: verify on the clock
- [ ] Slam: tap on top snoozes a ringing Kiosk Satellite alarm, double tap stops it; Vol ± change the ringing alarm's volume.
- [ ] Gestures from the on-clock settings screen and from HA (`/api/gesture`, the *Run … action* buttons).
- [ ] Home Assistant integration end to end: discovered via mDNS, *Show token*, entities, pushed events, device triggers.
- [ ] Dock light + phone charging through HA (clock back on the dock).
- [ ] Voice after a cold boot (mic-switch boot fix is in build 3: seen working on the first boot).
- [ ] Kiosk Satellite auto-update installs silently (needs a new Kiosk release to happen).
- [ ] Wi-Fi reconnect, Bluetooth audio, 72 h stability, idle RAM/CPU.
- [ ] **Factory reset** from Settings and recovery: everything comes back without manual steps. Check the leftover Device Owner (`com.google.assistant.core`, no longer in the image) doesn't get in the way.

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
