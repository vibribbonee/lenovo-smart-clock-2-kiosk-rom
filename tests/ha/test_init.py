"""Setup, entities, commands and webhook events against a mocked ClockBridge."""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntryState
from homeassistant.core import HomeAssistant
from homeassistant.helpers import device_registry as dr

from pytest_homeassistant_custom_component.common import async_capture_events
from pytest_homeassistant_custom_component.test_util.aiohttp import AiohttpClientMocker

from .conftest import BASE, DEVICE_ID, DOMAIN


def _device(hass: HomeAssistant) -> dr.DeviceEntry:
    entry = hass.config_entries.async_entries(DOMAIN)[0]
    (device,) = dr.async_entries_for_config_entry(dr.async_get(hass), entry.entry_id)
    assert (DOMAIN, DEVICE_ID) in device.identifiers
    return device


def _calls(mock: AiohttpClientMocker, method: str, path: str) -> list:
    return [c for c in mock.mock_calls if c[0] == method and str(c[1]).startswith(f"{BASE}{path}")]


async def _setup(hass: HomeAssistant, entry) -> None:
    await hass.config.async_update(internal_url="http://192.0.2.10:8123")
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()


async def test_setup_and_entities(hass: HomeAssistant, mock_clock: AiohttpClientMocker, config_entry) -> None:
    await _setup(hass, config_entry)
    assert config_entry.state is ConfigEntryState.LOADED
    assert config_entry.runtime_data.push

    # The clock was told where to push events.
    hook = _calls(mock_clock, "POST", "/api/webhook")
    assert str(hook[0][1].query["url"]).startswith("http://192.0.2.10:8123/api/webhook/")

    light = hass.states.get("light.smart_clock_2_ab12_dock_light")
    assert light.state == "on"
    assert light.attributes["brightness"] == 76  # level 3
    assert hass.states.get("binary_sensor.smart_clock_2_ab12_docked").state == "on"
    assert hass.states.get("binary_sensor.smart_clock_2_ab12_phone_on_pad").state == "on"
    assert hass.states.get("binary_sensor.smart_clock_2_ab12_phone_charging").state == "off"
    assert hass.states.get("binary_sensor.smart_clock_2_ab12_mic_muted").state == "off"
    assert hass.states.get("select.smart_clock_2_ab12_mute_indicator").state == "pill"
    assert hass.states.get("select.smart_clock_2_ab12_mute_indicator_colour").state == "amber"
    assert hass.states.get("select.smart_clock_2_ab12_mute_indicator_size").state == "default"
    assert hass.states.get("select.smart_clock_2_ab12_hold_volume_up").state == "voice"  # unset = default
    assert hass.states.get("select.smart_clock_2_ab12_hold_volume_down").state == "ha_event"
    assert hass.states.get("select.smart_clock_2_ab12_tap_on_top").state == "alarm_snooze"  # unset = default
    assert hass.states.get("select.smart_clock_2_ab12_double_tap_on_top").state == "screen_toggle"
    assert hass.states.get("select.smart_clock_2_ab12_double_tap_speed").state == "relaxed"
    assert hass.states.get("sensor.smart_clock_2_ab12_kiosk_satellite_version").state == "2026.11.29"

    device = _device(hass)
    assert device.sw_version == "0.9.0"


async def test_commands(hass: HomeAssistant, mock_clock: AiohttpClientMocker, config_entry) -> None:
    await _setup(hass, config_entry)

    await hass.services.async_call(
        "light", "turn_on", {"entity_id": "light.smart_clock_2_ab12_dock_light", "brightness": 255}, blocking=True
    )
    assert _calls(mock_clock, "POST", "/api/dock/light")[-1][1].query["level"] == "10"
    assert hass.states.get("light.smart_clock_2_ab12_dock_light").attributes["brightness"] == 255

    await hass.services.async_call(
        "light", "turn_off", {"entity_id": "light.smart_clock_2_ab12_dock_light"}, blocking=True
    )
    assert _calls(mock_clock, "POST", "/api/dock/light")[-1][1].query["level"] == "0"

    await hass.services.async_call(
        "select", "select_option",
        {"entity_id": "select.smart_clock_2_ab12_mute_indicator_size", "option": "large"}, blocking=True,
    )
    q = _calls(mock_clock, "POST", "/api/settings")[-1][1].query
    assert (q["key"], q["value"]) == ("kioskclock_mic_size", "22")

    await hass.services.async_call(
        "select", "select_option",
        {"entity_id": "select.smart_clock_2_ab12_mute_indicator_colour", "option": "red"}, blocking=True,
    )
    assert _calls(mock_clock, "POST", "/api/settings")[-1][1].query["value"] == "#D93025"

    await hass.services.async_call(
        "button", "press", {"entity_id": "button.smart_clock_2_ab12_screen_off"}, blocking=True
    )
    assert _calls(mock_clock, "POST", "/api/screen/off")

    await hass.services.async_call(
        "button", "press", {"entity_id": "button.smart_clock_2_ab12_run_volume_hold_action"}, blocking=True
    )
    assert _calls(mock_clock, "POST", "/api/gesture")[-1][1].query["name"] == "hold_up"

    await hass.services.async_call(
        "select", "select_option",
        {"entity_id": "select.smart_clock_2_ab12_tap_on_top", "option": "alarm_stop"}, blocking=True,
    )
    q = _calls(mock_clock, "POST", "/api/settings")[-1][1].query
    assert (q["key"], q["value"]) == ("kioskclock_tap_single", "alarm_stop")


async def test_webhook_events(hass: HomeAssistant, mock_clock, config_entry, hass_client_no_auth) -> None:
    await _setup(hass, config_entry)
    events = async_capture_events(hass, "kioskclock_event")
    client = await hass_client_no_auth()
    url = f"/api/webhook/{config_entry.data['webhook_id']}"

    resp = await client.post(url, json={"type": "tap", "value": "double", "device": "smart-clock-2", "ts": 1})
    assert resp.status == 200
    resp = await client.post(url, json={"type": "button", "value": "hold_up:voice"})
    assert resp.status == 200
    await hass.async_block_till_done()

    device = _device(hass)
    assert [(e.data["trigger"], e.data["device_id"]) for e in events] == [
        ("tap_double", device.id), ("hold_up", device.id),
    ]
    tap = hass.states.get("event.smart_clock_2_ab12_tap")
    assert tap.attributes["event_type"] == "double"
    button = hass.states.get("event.smart_clock_2_ab12_button_hold")
    assert button.attributes["event_type"] == "hold_up"
    assert button.attributes["action"] == "voice"


async def test_device_trigger(hass: HomeAssistant, mock_clock, config_entry, hass_client_no_auth) -> None:
    from homeassistant.setup import async_setup_component

    await _setup(hass, config_entry)
    device = _device(hass)
    fired = async_capture_events(hass, "test_fired")
    assert await async_setup_component(hass, "automation", {"automation": [{
        "triggers": [{"trigger": "device", "domain": DOMAIN, "device_id": device.id, "type": "mic_muted"}],
        "actions": [{"event": "test_fired"}],
    }]})
    client = await hass_client_no_auth()
    url = f"/api/webhook/{config_entry.data['webhook_id']}"
    await client.post(url, json={"type": "mic", "value": "unmuted"})
    await client.post(url, json={"type": "mic", "value": "muted"})
    await hass.async_block_till_done()
    assert len(fired) == 1


async def test_auth_failure_starts_reauth(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker, config_entry) -> None:
    aioclient_mock.get(f"{BASE}/api/dock", status=401, json={"error": "unauthorized"})
    config_entry.add_to_hass(hass)
    await hass.config_entries.async_setup(config_entry.entry_id)
    await hass.async_block_till_done()
    assert config_entry.state is ConfigEntryState.SETUP_ERROR
    assert any(f["context"]["source"] == "reauth" for f in hass.config_entries.flow.async_progress())


async def test_old_clockbridge_polls_only(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker, config_entry) -> None:
    from .conftest import DOCK, SETTINGS, STATUS

    aioclient_mock.get(f"{BASE}/api/info", status=404, json={"error": "not found"})
    aioclient_mock.get(f"{BASE}/api/dock", json=DOCK)
    aioclient_mock.get(f"{BASE}/api/status", json=STATUS)
    aioclient_mock.get(f"{BASE}/api/settings", json=SETTINGS)
    aioclient_mock.post(f"{BASE}/api/webhook", status=404, json={"error": "not found"})
    await _setup(hass, config_entry)
    assert config_entry.state is ConfigEntryState.LOADED
    assert not config_entry.runtime_data.push
    # No gesture buttons or tap selects on a ClockBridge that can't run them.
    assert hass.states.get("button.smart_clock_2_ab12_run_volume_hold_action") is None
