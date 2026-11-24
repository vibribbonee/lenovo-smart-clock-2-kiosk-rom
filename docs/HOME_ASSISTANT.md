# Home Assistant integration (ClockBridge)

ClockBridge runs on the clock and offers a small HTTP API on port **8765** plus push events to a Home Assistant **webhook**. Every API call needs `Authorization: Bearer <token>`.

## Recommended: the Kiosk Clock integration (no YAML)
The repo contains a Home Assistant custom integration, `kioskclock` ([custom_components/kioskclock](../custom_components/kioskclock)). The clock shows up in HA by itself; you confirm it and type the API token once.

1. **Install** with [HACS](https://hacs.xyz): HACS → ⋮ → *Custom repositories* → `https://github.com/vibribbonee/lenovo-smart-clock-2-kiosk-rom`, type *Integration* → download **Kiosk Clock** → restart Home Assistant.
   Without HACS: copy `custom_components/kioskclock` into your HA `config/custom_components/` folder and restart.
2. **Add the clock.** HA finds it via mDNS (`_kioskclock._tcp`, ROM with ClockBridge 0.9.0 or newer) and offers it under *Settings → Devices & services → Discovered*. Click *Add*. Not discovered (other VLAN, older ROM)? *Add integration → Kiosk Clock* and enter the clock's IP address (shown on the clock under quick panel → **Clock**).
3. **Token.** On the clock: swipe up from the bottom edge → quick panel → **Clock** → **Show token**, and type it into HA (the spaces don't matter). On older ROMs the token is only in the log (`adb logcat -d | grep "API token"`) or set your own (see *Token* below).

The integration then registers its own webhook on the clock (no `set_webhook` needed; ClockBridge 0.9.0+), polls every 15 s, and refreshes instantly when the clock reports a change.

| Entity | What |
|---|---|
| `light.…_dock_light` | dock night light, levels 0–10 mapped to brightness |
| `binary_sensor.…_docked`, `…_phone_on_pad`, `…_phone_charging` | dock and wireless-charging pad |
| `binary_sensor.…_mic_muted` | mic switch on the back |
| `button.…_screen_on` / `…_screen_off` | wake / black screen (backlight 0) |
| `button.…_check_kiosk_update` | check GitHub for a newer Kiosk Satellite now |
| `button.…_run_volume_hold_action`, `…_run_volume_−_hold_action`, `…_run_tap_action`, `…_run_double_tap_action` | run whatever that gesture is set to, as if you did it on the clock (ClockBridge 0.9.0+) |
| `select.…_mute_indicator`, `…_colour`, `…_size` | mute indicator style |
| `select.…_hold_volume_up`, `…_hold_volume_down`, `…_tap_on_top`, `…_double_tap_on_top` | what each gesture does (see *Gestures*) |
| `sensor.…_kiosk_satellite_version`, `…_webview_version`, `…_kiosk_update_status` | diagnostics |
| `event.…_tap`, `…_button_hold`, `…_mic_switch` | taps (single/double), Vol± holds (attribute `action`), mic switch |

Kiosk Satellite brings its own entities for the screen, media volume, brightness and alarms (its ESPHome device); this integration doesn't duplicate those.

**Automations:** use the device triggers (*Top of the clock double-tapped*, *Volume + held*, *Mic switch set to mute*, *Phone placed on the pad*, *Undocked*, …) or the event entities. Every pushed event is also fired on the bus as `kioskclock_event` with `device_id`, `type`, `value` and `trigger`.
For a gesture that should only do something in HA, set it to *Home Assistant only* (`ha_event`). Gestures run on the clock, so they keep working when HA is down.

## Gestures
Four gestures, each set to one action (on the clock: quick panel → **Clock**; in HA: the selects above):

| Gesture | Default |
|---|---|
| Hold Vol + | `voice` |
| Hold Vol − | `dock_light` |
| Tap on top (a firm "slam") | `alarm_snooze` |
| Double tap on top | `alarm_stop` |

Actions: `alarm_snooze` / `alarm_stop` (a ringing Kiosk Satellite alarm, like Lenovo's hard tap; nothing otherwise), `voice`, `play_pause`, `mute` (speaker), `dock_light`, `night_light`, `screen_off`, `screen_toggle`, `ha_event` (only send it to HA), `none`.
Snooze/stop works offline: KioskNav presses the alarm screen's own Snooze / Stop button. While an alarm rings, Vol ± change the alarm's volume instead of media.

If the token changes, HA asks for the new one (*Reconfigure* / repair notice). Removing the integration clears the webhook on the clock.

## Token
ClockBridge generates a token on first start (`Show token` on the clock, or logged as `generated API token …`). To set your own (≥ 32 characters):
```bash
adb shell am broadcast -a org.kioskclock.bridge.COMMAND -n org.kioskclock.bridge/.CommandReceiver --es cmd set_token --es token YOUR_LONG_RANDOM_TOKEN
```

## Fallback: manual YAML
Without the integration, HA talks to the API with `rest` / `rest_command` (example at the end of this page).
1. **Webhook** (for instant events: taps, dock, phone charging, mic switch, button holds):
   ```bash
   adb shell am broadcast -a org.kioskclock.bridge.COMMAND -n org.kioskclock.bridge/.CommandReceiver --es cmd set_webhook --es url http://homeassistant.local:8123/api/webhook/smart_clock_events
   ```
   (or `POST /api/webhook?url=…`). Don't combine this with the integration: there is one webhook per clock, and the integration replaces it.
2. Put the token in `secrets.yaml`: `clock_auth: "Bearer YOUR_LONG_RANDOM_TOKEN"`, and the clock's address (give it a fixed DHCP lease).
3. On the clock itself, the settings can also be changed with `adb shell settings put global <key> <value>` or the on-clock settings screen.

## API
| Call | Does |
|---|---|
| `GET /api/dock` | `{"dock":true,"light":0..10,"phone_present":bool,"phone_charging":bool,"charger_state":n}` |
| `POST /api/dock/light?level=N` | night light 0–10 |
| `POST /api/screen/off` / `POST /api/screen/on` | black screen (backlight 0) / wake |
| `GET /api/status` | mic anchor, `mic_muted` (mic switch: true/false, null if unknown), dock firmware, WebView and Kiosk Satellite versions, update state |
| `POST /api/update/check` | check GitHub for a newer Kiosk Satellite now |
| `GET /api/settings` | the settings below |
| `POST /api/settings?key=K&value=V` | change one; applies live. `value=default` resets it (0.9.0+) |
| `GET /api/info` | `{"id","name","model","version","api"}` device identity (0.9.0+) |
| `GET /api/webhook` / `POST /api/webhook?url=U` | read / set the event webhook; empty `url` clears it (0.9.0+) |
| `POST /api/gesture?name=G` | run gesture G's action (`hold_up`, `hold_down`, `tap_single`, `tap_double`); not echoed as an event (0.9.0+) |
| `POST /api/action?name=A` | run one action directly (0.9.0+) |

Discovery: ClockBridge 0.9.0+ advertises `_kioskclock._tcp` on port 8765 via mDNS, TXT `id`, `model`, `version`, `api`.

Settings (`/api/settings`):
| Key | Values | Default |
|---|---|---|
| `kioskclock_mic_indicator` | `dot`, `dot_quiet`, `google`, `pill`, `edge`, `banner`, `hidden` | `dot` |
| `kioskclock_mic_color` | `#RRGGBB` or `#AARRGGBB` | `#D93025` |
| `kioskclock_mic_size` | dot/badge size in px (1–99) | 14 (dot) / 36 (badge) |
| `kioskclock_hold_volume_up` | `alarm_snooze`, `alarm_stop`, `voice`, `play_pause`, `mute`, `dock_light`, `night_light`, `screen_off`, `screen_toggle`, `ha_event` (only sends it to HA), `none` | `voice` |
| `kioskclock_hold_volume_down` | same | `dock_light` |
| `kioskclock_tap_single` | same (0.9.0+) | `alarm_snooze` |
| `kioskclock_tap_double` | same (0.9.0+) | `alarm_stop` |

## configuration.yaml example (fallback)
```yaml
rest:
  - resource: http://CLOCK_IP:8765/api/dock
    headers: { Authorization: !secret clock_auth }
    scan_interval: 10
    sensor:
      - name: Clock dock light level
        value_template: "{{ value_json.light }}"
    binary_sensor:
      - name: Clock docked
        value_template: "{{ value_json.dock }}"
      - name: Phone on clock pad
        value_template: "{{ value_json.phone_present }}"
      - name: Phone charging on clock
        device_class: battery_charging
        value_template: "{{ value_json.phone_charging }}"

rest_command:
  clock_dock_light:
    url: "http://CLOCK_IP:8765/api/dock/light?level={{ level }}"
    method: post
    headers: { Authorization: !secret clock_auth }
  clock_screen:
    url: "http://CLOCK_IP:8765/api/screen/{{ state }}"   # on | off
    method: post
    headers: { Authorization: !secret clock_auth }
  clock_setting:
    url: "http://CLOCK_IP:8765/api/settings?key={{ key }}&value={{ value | urlencode }}"
    method: post
    headers: { Authorization: !secret clock_auth }

light:
  - platform: template
    lights:
      clock_dock_light:
        friendly_name: Clock dock light
        level_template: "{{ (states('sensor.clock_dock_light_level') | int(0) * 25.5) | int }}"
        value_template: "{{ states('sensor.clock_dock_light_level') | int(0) > 0 }}"
        turn_on:
          action: rest_command.clock_dock_light
          data: { level: "{{ ((brightness | default(255)) / 25.5) | round(0) | int }}" }
        set_level:
          action: rest_command.clock_dock_light
          data: { level: "{{ (brightness / 25.5) | round(0) | int }}" }
        turn_off:
          action: rest_command.clock_dock_light
          data: { level: 0 }
```

## Webhook automations (fallback)
Events arrive as JSON `{"type": "...", "value": "...", "device": "smart-clock-2", "ts": ...}`:
`tap` (`single`/`double`), `alarm_volume` (0–100, Vol ± while an alarm rings), `dock` (`attached`/`removed`), `phone` (`none`/`present`/`charging`), `mic` (`muted`/`unmuted`), `button` (`hold_up:voice`, …), `volume` (0–100).

```yaml
automation:
  - alias: Clock double tap toggles bedroom light
    triggers:
      - trigger: webhook
        webhook_id: smart_clock_events
        allowed_methods: [POST]
        local_only: true
    conditions:
      - "{{ trigger.json.type == 'tap' and trigger.json.value == 'double' }}"
    actions:
      - action: light.toggle
        target: { entity_id: light.bedroom }

  - alias: Clock mic indicator less visible at night
    triggers:
      - trigger: time
        at: "22:30:00"
    actions:
      - action: rest_command.clock_setting
        data: { key: kioskclock_mic_indicator, value: dot_quiet }
```
