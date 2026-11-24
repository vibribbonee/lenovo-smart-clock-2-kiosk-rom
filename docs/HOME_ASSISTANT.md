# Home Assistant integration (ClockBridge)

ClockBridge runs on the clock and offers a small HTTP API on port **8765** plus push events to a Home Assistant **webhook**. Every API call needs `Authorization: Bearer <token>`.

## One-time setup
1. **Token.** ClockBridge generates one on first start (logged as `generated API token …`). Set your own (≥ 32 characters) instead:
   ```bash
   adb shell am broadcast -a org.kioskclock.bridge.COMMAND -n org.kioskclock.bridge/.CommandReceiver --es cmd set_token --es token YOUR_LONG_RANDOM_TOKEN
   ```
2. **Webhook** (for instant events: taps, dock, phone charging, mic switch, button holds):
   ```bash
   adb shell am broadcast -a org.kioskclock.bridge.COMMAND -n org.kioskclock.bridge/.CommandReceiver --es cmd set_webhook --es url http://homeassistant.local:8123/api/webhook/smart_clock_events
   ```
3. Put the token in `secrets.yaml`: `clock_auth: "Bearer YOUR_LONG_RANDOM_TOKEN"`, and the clock's address (give it a fixed DHCP lease).

## API
| Call | Does |
|---|---|
| `GET /api/dock` | `{"dock":true,"light":0..10,"phone_present":bool,"phone_charging":bool,"charger_state":n}` |
| `POST /api/dock/light?level=N` | night light 0–10 |
| `POST /api/screen/off` / `POST /api/screen/on` | black screen (backlight 0) / wake |
| `GET /api/status` | mic anchor, dock firmware, WebView and Kiosk Satellite versions, update state |
| `POST /api/update/check` | check GitHub for a newer Kiosk Satellite now |
| `GET /api/settings` | the settings below |
| `POST /api/settings?key=K&value=V` | change one; applies live |

Settings (`/api/settings`):
| Key | Values | Default |
|---|---|---|
| `kioskclock_mic_indicator` | `dot`, `dot_quiet`, `google`, `pill`, `edge`, `banner`, `hidden` | `dot` |
| `kioskclock_mic_color` | `#RRGGBB` or `#AARRGGBB` | `#D93025` |
| `kioskclock_mic_size` | dot/badge size in px (1–99) | 14 (dot) / 36 (badge) |
| `kioskclock_hold_volume_up` | `voice`, `dock_light`, `screen_off`, `none` | `voice` |
| `kioskclock_hold_volume_down` | same | `dock_light` |

## configuration.yaml example
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

## Webhook automations
Events arrive as JSON `{"type": "...", "value": "...", "device": "smart-clock-2", "ts": ...}`:
`tap` (`single`/`double`), `dock` (`attached`/`removed`), `phone` (`none`/`present`/`charging`), `mic` (`muted`/`unmuted`), `button` (`hold_up:voice`, …), `volume` (0–100).

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
