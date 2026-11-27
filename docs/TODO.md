# TODO (updated 2026-11-27, v1.1.1)

Release history: [CHANGELOG.md](../CHANGELOG.md).

## Before calling 1.x final (needs the clock)
- [ ] **Kiosk Satellite defaults out of the box** through its `ProvisionActivity` (`ks.provision`, needs `DUMP`, which a priv-app can hold): microphone gain (~+17 dB), Clock screensaver with Night mode. The payload format is undocumented: test over adb with a harmless payload first. Until then the README tells users to set both by hand.
- [ ] **Factory reset** from Settings: everything comes back without manual steps (home app, keyboard, nav bar, tour, ClockCore's mic permission, Bluetooth name). Check the leftover Device Owner (`com.google.assistant.core`, no longer in the image) doesn't get in the way.
- [ ] Pairing a fresh HA through *Pair on the clock* (Allow), not the token.
- [ ] Alarm with *Wake-up light: Both* (dock + a lamp picked under *Kiosk Clock → Configure*); double tap = stop; a 1-minute timer stopped by a tap; taps with nothing ringing don't snooze.
- [ ] SmartTube: Clock settings → Extras → Install; open it; how usable touch is; *Remote control* → link from the YouTube app.
- [ ] Mini player / ♫ / full player on screen with Bluetooth audio; *Pop-up player: Off*.
- [ ] Long-run checks: Wi-Fi reconnect, 72 h stability, idle RAM/CPU; Kiosk Satellite updating itself when it releases.

## Next (1.2)
- [ ] The clock as a Bluetooth → Music Assistant **line-in** (Sendspin `source@v1` role): capture the A2DP sink audio (privileged CAPTURE_AUDIO_OUTPUT / remote submix) and offer it as a Live Input, so a phone paired to the clock plays on every speaker. Needs the Sendspin source protocol and a latency test.
- [ ] Dutch translation (HA integration: one file; the clock's own screens: move their texts to Android string resources first).
- [ ] Onboarding: a QR code for the Kiosk Satellite Remote page.

## Backlog
- [ ] Flashing without `sudo` (mtkclient + libusb permissions on macOS/Linux) and Windows support (needs a Windows machine to test).
- [ ] WebView: a stable official download source would allow auto-fetch (APKMirror blocks scripted downloads); keep the manual file option.
- [ ] Remove the 5 disabled system apps that still show as *Disabled* in Android's Settings (needs modifying the system partition, which builds keep identical to stock).
- [ ] Post the mic bug report to Kiosk Satellite (`docs/reports/kiosk-satellite-mic-ladder.md`); if they record at 48 kHz, ClockCore can go.
- [ ] Reproducible app builds (fixed zip timestamps).
- [ ] Later: try a GSI/LineageOS via DSU without touching the ROM.

## Decided / not possible
- ADB stays on (USB only, through the pogo pins: physical access).
- Casting: no Google Cast receiver. YouTube via the SmartTube extra; Music Assistant / Sendspin, DLNA and HA's `media_player` via Kiosk Satellite.
- Bluetooth: the clock receives audio (speaker) but can't send it to Bluetooth speakers (no A2DP source in this firmware). No absolute volume: on this Android 10 stack its handshake made phone volume changes arrive up to ~10 s late; without it they take ~0.5 s (the Bluetooth audio buffer).
- Not possible: flashing through the dock (its USB-A port is power only); a shorter or no orange unlock warning (the boot loader is signature-checked).

## Known limitations
- Brightness nudges in Auto mode are recorded but not applied to the curve (`mUserLux=-1`); Kiosk Satellite's own brightness control covers this.
- Alarm volume: Kiosk Satellite sets the alarm stream to its own *Alarm volume* setting at every ring; change it there (Vol ± during a ring works for that ring).
- Kiosk Satellite already exposes screen, media volume, brightness and alarms to HA (its ESPHome device); the Kiosk Clock integration adds what only ClockBridge knows (dock, mic switch, gestures, wake-up light, updates).
- Album art over Bluetooth depends on the phone; title and artist always arrive.

## Notes
- **Keys** are backed up in the private repo `vibribbonee/lenovo-smart-clock-2-keys` (SjorsMaster has access). One set per builder, not per clock.
- **Wake word after a wipe** needs Kiosk Satellite's mic gain raised (~+17 dB) until the provisioning defaults above land.
