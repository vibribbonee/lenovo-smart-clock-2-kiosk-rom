"""Automatic app updates switch: unset = on; turning it off writes 0 on the clock."""

from __future__ import annotations

from homeassistant.core import HomeAssistant

from .conftest import BASE
from .test_init import _setup


async def test_app_updates_switch(hass: HomeAssistant, mock_clock, config_entry) -> None:
    await _setup(hass, config_entry)
    assert hass.states.get("switch.smart_clock_2_ab12_automatic_app_updates").state == "on"
    assert hass.states.get("select.smart_clock_2_ab12_media_controls").state == "auto"
    await hass.services.async_call(
        "switch", "turn_off", {"entity_id": "switch.smart_clock_2_ab12_automatic_app_updates"}, blocking=True
    )
    q = [c for c in mock_clock.mock_calls if c[0] == "POST" and str(c[1]).startswith(f"{BASE}/api/settings")][-1][1].query
    assert (q["key"], q["value"]) == ("kioskclock_app_updates", "0")
    assert hass.states.get("switch.smart_clock_2_ab12_automatic_app_updates").state == "off"
