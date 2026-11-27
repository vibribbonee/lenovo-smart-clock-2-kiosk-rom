# Changelog

Clocks on 1.0.1 or newer update the ROM's apps by themselves over Wi-Fi. Releases marked **flash** also change the image: rebuild and `./clockrom.sh flash` (keeps your data).

## 1.1.1 (2026-11-27)
- The Kiosk Clock integration has its own icon in Home Assistant (2026.3+).
- Changelog, issue templates, contributing guide; the TODO list cleaned up.
- No changes on the clock.

## 1.1.0 (2026-11-25) — flash
- Phone volume over Bluetooth reacts in about half a second (the normal Bluetooth audio delay) instead of up to ~10 s: no more "absolute volume" handshake. The phone sets its own loudness, like most Bluetooth speakers.
- When a phone connects, the clock's own volume goes up to at least 70 % instead of starting near silent.
- The volume bar shows for every volume change (Home Assistant, Music Assistant, other apps), not only the clock's buttons.

## 1.0.3 (2026-11-25)
- Settings you change stay changed: a re-run of the setup after an update only fills in settings you never set (a chosen mute-indicator style used to reset to *dot*).
- Mini player: a swipe hides it until that music stops; *Pop-up player: On/Off* in the full player.

## 1.0.2 (2026-11-25) — flash
- Bluetooth speaker: the phone's track info (title, artist, position) reaches the clock's media controls, and play/pause/skip on the clock control the phone (AVRCP Controller instead of Target).
- Clearer instructions when a flash finds the clock still in download mode.

## 1.0.1 (2026-11-25)
- Updates work on the clock: Android 10 reported downloaded APKs as "unexpected signers", so the updaters rejected everything. Clocks on 1.0.0 need this one update by hand.

## 1.0.0 (2026-11-25) — flash
- **Home Assistant integration** (HACS): found automatically, paired by tapping *Allow* on the clock; dock light, dock/phone/mic sensors, settings, tap and button events, device triggers, diagnostics, an update entity for the clock's apps.
- **Gestures:** hold Vol + / Vol −, tap and double tap on top, each set to an action; separate taps while an alarm or timer rings (snooze / stop, works offline); adjustable double-tap speed.
- **Wake-up light:** the dock light and Home Assistant lights fade in before an alarm.
- **Media controls** for anything that plays, a **Bluetooth speaker** tile, the **SmartTube** extra (YouTube without Google services).
- **Updates over Wi-Fi** for the ROM's apps; ClockCore keeps the microphone fix in its own small app.
- First-boot tour, troubleshooting guide, FAQ, example dashboards, gesture blueprint.
- Unused stock apps disabled or removed (Cast, Assistant, Lenovo MDM and updaters, test apps).
