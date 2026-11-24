# TODO (after ROM build 1, 2026-11-24)

## A. Build 2 — out-of-the-box setup (no manual steps after flash or factory reset)
- [ ] Kiosk Satellite is the home app on first boot: ClockBridge enables `me.jxl.kiosk_satellite/.HomeAlias` (add `CHANGE_COMPONENT_ENABLED_STATE` to the privapp allowlist).
- [ ] Simple Keyboard enabled + default on first boot.
- [ ] Kiosk Satellite's special access granted on first boot, only what it needs: its accessibility services, notification listener (media), draw over apps, modify system settings (brightness), battery-optimisation exemption.
- [ ] Disable unused stock apps on first boot (Browser, Calendar, Contacts, Music, CarBTDemo, MeshTestProvisioner, TalkBack, YGPS, EngineerMode?, MTPDemo…); remove them from the image after a test round.
- [ ] Sensible defaults: screen timeout / stay awake, time zone and 24-hour clock, locale, DHCP hostname.

## B. Fix what's broken
- [ ] **Volume:** the scale is 0–100, so step ~6–8 per press; show a small volume bar (KioskNav).
- [ ] **Brightness and auto-brightness:** find the backlight path (Google apps drove it on stock); light sensor exists (sensor handle 5); manual slider + auto mode.
- [ ] **Mic mute switch:** verify it's detected on the ROM and that Kiosk Satellite follows it.
- [x] **Tap to snooze:** taps are reported reliably on the ROM (no Assistant). Tap = snooze, double tap = stop a ringing Kiosk Satellite alarm (KioskNav presses its buttons); configurable like the holds.

## C. Verify on the ROM (test plan)
- [ ] Voice works after a reboot (ClockBridge anchor wins the race now that it's persistent).
- [ ] Dock light + phone charging through ClockBridge (priv-app → SELinux allows it); HA API and webhook from Home Assistant.
- [ ] Kiosk Satellite auto-update installs silently.
- [ ] Wi-Fi reconnect, Bluetooth, audio playback, touchscreen, 72 h stability, idle RAM/CPU.
- [ ] **Factory reset** from Settings and recovery → everything comes back without manual steps.

## D. Decisions / options
- [ ] **Casting:** gone with the Cast receiver. Options: Kiosk Satellite's own media player for HA (SendSpin), or try re-adding Cast (needs Google Home setup, which depended on Assistant — likely not viable).
- [ ] ADB in the release build: off by default, on, or Wi-Fi with key; optional this-Mac key in a debug variant.
- [ ] Lenovo MDM agent (phones home, has OTA code) and the Google OEM app: disable or keep.
- [ ] Remove the leftover stock apps permanently (after B/C pass).

## F. Findings during build 2 testing
- **Wake word after a wipe:** Kiosk Satellite's own config (incl. **mic gain**, ~17 dB on the old setup) is wiped with /data. With the default gain "OK Nabu" did not trigger; raising the gain in Kiosk Satellite fixed it. After a factory reset: restore/redo Kiosk Satellite's config. The mic stream itself was correct (48 kHz stereo, ClockBridge first).
- Log buffer is 64 KiB on Android Go: set `persist.logd.size` to 1 MiB in the ROM for debuggability.

## G. Build 2 results (flashed 2026-11-24 with `update`, data kept)
- First flash attempt: USB dropped at 4 % of super (only identical vendor/metadata bytes written, clock kept booting build 1); retry succeeded.
- ✅ First-boot setup applied every item (home = Kiosk HomeAlias, Simple Keyboard default, lock screen off, 24 h, Europe/Amsterdam, timeout never, stay awake, 13 apps disabled, logd 1 MiB).
- ✅ User-confirmed: home, keyboard, volume buttons, dock light slider.
- ✅ Auto-brightness works (dark room 0–2 lux → 3/255; lamps ~75 lux → 88; ~174 lux → 138).
- ⚠️ Known limitation: brightness nudges in Auto mode are recorded (`mLastUserSetScreenBrightness`) but not applied to the curve (`mUserLux=-1`); `screen_auto_brightness_adj` is ignored too. Kiosk Satellite's own brightness control covers this.
- To do next build: remove the 13 disabled apps from the image (they stay listed as "Disabled" in Settings), taller panel sliders, optional volume click.

## E. Nice to have / housekeeping
- [ ] Post the mic bug report to Kiosk Satellite (docs/reports/).
- [ ] Home Assistant config examples (REST sensors, light control, webhook automations) in docs.
- [ ] Reproducible app builds (fixed zip timestamps).
- [ ] Commit ROM1_FIRST_BOOT.md; back up `keys/`.
- [ ] Later: try a GSI/LineageOS via DSU without touching our ROM.
- [ ] Not possible: shorter/no orange unlock warning (LK is signature-checked by the preloader).
