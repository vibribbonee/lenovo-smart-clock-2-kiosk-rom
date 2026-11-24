# FAQ: what's different from the stock clock

| Stock feature | In this ROM |
|---|---|
| Google Assistant | Replaced by **Home Assistant Assist** through Kiosk Satellite (wake word *"OK Nabu"* and others, local or cloud voice, your own pipelines). |
| Hard tap to snooze / stop | Yes: tap the top while an alarm or timer rings to snooze, double tap to stop (configurable). Works without Wi-Fi. |
| Alarms, sunrise alarm | Kiosk Satellite's alarms (on the clock, offline) with its screen *sunrise*; plus the ROM's **wake-up light** for the dock light and Home Assistant lights. |
| Good-morning routine | A Home Assistant automation on the alarm: Kiosk Satellite reports *Alarm ringing* and *Next alarm* to HA, and the Kiosk Clock integration adds tap and button triggers. |
| Night mode / screen off in the dark | Kiosk Satellite's **Clock screensaver** → *Night mode*: dims at night and can *turn the screen off at night after* a few minutes (light level about 5 lx). Turn on *Use a black screen instead* if it asks for a device administrator. |
| Auto brightness | Yes (Android's light-sensor curve, or Kiosk Satellite's *Adaptive brightness*). |
| Dock night light, wireless charging | Yes: quick panel slider, gestures, Home Assistant light and sensors. |
| Volume buttons | Yes, with a click. While an alarm rings they set its volume. Hold either for a shortcut. |
| Mic switch | Yes: it mutes the microphone in hardware; a red dot (or another style) shows it. |
| Chromecast / "cast to the clock" | **Not available** (the Google Cast receiver needs Google's setup, which this ROM removes). Instead Kiosk Satellite is a media player for Home Assistant: **Music Assistant / Sendspin**, **DLNA** (cast from apps like BubbleUPnP) and Home Assistant's `media_player` actions. |
| Bluetooth speaker mode (phone → clock) | **Yes** (A2DP sink, tested): quick panel → hold the Bluetooth tile → *Pair new device* (the clock is discoverable while that screen is open) → pair *LenovoCD-24502F* from your phone → play. A one-tap "Bluetooth speaker" tile is planned. |
| Phone → whole house | Music Assistant's *Spotify Connect* and *AirPlay Receiver* plugins send your phone's audio to any Music Assistant player, the clock included (Kiosk Satellite is a Sendspin player). |
| YouTube, Google Photos frames | Not included. Kiosk Satellite can show photo albums (local, Immich, URLs) and HA camera streams as screensavers. |
| Google Home app setup | Not needed: set up from the clock itself and Kiosk Satellite's Remote page (`http://<clock-ip>:2324`) on your phone. |
| Languages | The ROM's own screens and the HA integration are English. Kiosk Satellite and Home Assistant have their own translations; Android's language is in Settings. |
| Updates | The ROM's apps and Kiosk Satellite update themselves over Wi-Fi; Home Assistant shows *Kiosk Clock apps* updates. Only image changes need a USB flash. |

Something not here? See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) or open an issue.
