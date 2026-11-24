"""Wake-up light: the clock pushes start/cancel, the integration fades the chosen HA lights."""

from __future__ import annotations

from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType

from pytest_homeassistant_custom_component.common import async_mock_service

from .test_init import _setup


async def _hook(hass, entry, hass_client_no_auth, value: str) -> None:
    client = await hass_client_no_auth()
    resp = await client.post(f"/api/webhook/{entry.data['webhook_id']}", json={"type": "wake_light", "value": value})
    assert resp.status == 200
    await hass.async_block_till_done()


async def test_wake_settings_entities(hass: HomeAssistant, mock_clock, config_entry) -> None:
    await _setup(hass, config_entry)
    assert hass.states.get("select.smart_clock_2_ab12_wake_up_light").state == "both"
    assert hass.states.get("number.smart_clock_2_ab12_wake_up_light_fade_length").state == "20.0"  # unset = default
    assert hass.states.get("number.smart_clock_2_ab12_wake_up_light_dock_limit").state == "4.0"

    await hass.services.async_call(
        "number", "set_value", {"entity_id": "number.smart_clock_2_ab12_wake_up_light_fade_length", "value": 30},
        blocking=True,
    )
    q = [c for c in mock_clock.mock_calls if c[0] == "POST" and "/api/settings" in str(c[1])][-1][1].query
    assert (q["key"], q["value"]) == ("kioskclock_wake_minutes", "30")


async def test_options_pick_lights(hass: HomeAssistant, mock_clock, config_entry) -> None:
    await _setup(hass, config_entry)
    result = await hass.config_entries.options.async_init(config_entry.entry_id)
    assert result["type"] is FlowResultType.FORM
    result = await hass.config_entries.options.async_configure(
        result["flow_id"], {"wake_lights": ["light.bedroom"], "wake_max_pct": 60}
    )
    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert config_entry.options == {"wake_lights": ["light.bedroom"], "wake_max_pct": 60}


async def test_fade_and_cancel(hass: HomeAssistant, mock_clock, config_entry, hass_client_no_auth) -> None:
    config_entry.add_to_hass(hass)
    hass.config_entries.async_update_entry(config_entry, options={"wake_lights": ["light.bedroom"], "wake_max_pct": 60})
    await _setup(hass, config_entry)
    hass.states.async_set("light.bedroom", "off", {"supported_features": 32})
    on: list = []
    off = async_mock_service(hass, "light", "turn_off")

    async def turn_on(call):  # behaves like a real light: it is on afterwards
        on.append(call)
        old = hass.states.get("light.bedroom")
        hass.states.async_set("light.bedroom", "on", old.attributes if old else {})

    hass.services.async_register("light", "turn_on", turn_on)

    # A 1 s fade: starts at 1 %, ends at the 60 % limit, with transitions on the way.
    await _hook(hass, config_entry, hass_client_no_auth, "start:1")
    task = config_entry.runtime_data.wake._task
    await task
    pcts = [c.data["brightness_pct"] for c in on]
    assert pcts[0] == 1 and pcts[-1] == 60 and pcts == sorted(pcts)
    assert "transition" in on[0].data and "transition" not in on[-1].data

    # Cancelled mid-fade (alarm skipped): a light that was off goes off again.
    hass.states.async_set("light.bedroom", "off", {"supported_features": 0})
    await _hook(hass, config_entry, hass_client_no_auth, "start:600")
    await _hook(hass, config_entry, hass_client_no_auth, "cancel")
    assert off and off[-1].data["entity_id"] == ["light.bedroom"]
