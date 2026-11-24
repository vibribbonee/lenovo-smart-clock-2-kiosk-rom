"""Update entity: GitHub's latest release vs the clock's ClockBridge version; Install asks the clock."""

from __future__ import annotations

from unittest.mock import patch

from homeassistant.core import HomeAssistant

from .conftest import BASE
from .test_init import _setup


async def test_update_entity(hass: HomeAssistant, mock_clock, config_entry) -> None:
    await _setup(hass, config_entry)
    state = hass.states.get("update.smart_clock_2_ab12_kiosk_clock_apps")
    assert state.state == "on"
    assert state.attributes["installed_version"] == "1.0.0"
    assert state.attributes["latest_version"] == "1.0.1"

    # The clock installs 1.0.1 and reports it on the next poll.
    mock_clock.clear_requests()
    from .conftest import DOCK, INFO, RELEASE, RELEASES, SETTINGS, STATUS
    mock_clock.get(f"{BASE}/api/info", json=INFO)
    mock_clock.get(f"{BASE}/api/dock", json=DOCK)
    mock_clock.get(f"{BASE}/api/status", json={**STATUS, "bridge_version": "1.0.1"})
    mock_clock.get(f"{BASE}/api/settings", json=SETTINGS)
    mock_clock.post(f"{BASE}/api/update/check", json={"started": True})
    mock_clock.get(RELEASES, json=RELEASE)
    with patch("custom_components.kioskclock.update.INSTALL_POLL_S", 0):
        await hass.services.async_call(
            "update", "install", {"entity_id": "update.smart_clock_2_ab12_kiosk_clock_apps"}, blocking=True
        )
    assert [c for c in mock_clock.mock_calls if c[0] == "POST" and str(c[1]) == f"{BASE}/api/update/check"]
    state = hass.states.get("update.smart_clock_2_ab12_kiosk_clock_apps")
    assert state.state == "off" and state.attributes["installed_version"] == "1.0.1"
