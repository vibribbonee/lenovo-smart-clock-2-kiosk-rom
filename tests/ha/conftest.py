"""Fixtures for the kioskclock integration tests."""

from __future__ import annotations

from pathlib import Path

import pytest

# pytest-homeassistant-custom-component ships its own (empty) custom_components package; add ours to it.
import custom_components

_OURS = str(Path(__file__).resolve().parents[2] / "custom_components")
if _OURS not in custom_components.__path__:
    custom_components.__path__.append(_OURS)

from homeassistant.const import CONF_HOST, CONF_PORT

from pytest_homeassistant_custom_component.common import MockConfigEntry
from pytest_homeassistant_custom_component.test_util.aiohttp import AiohttpClientMocker

DOMAIN = "kioskclock"
HOST = "192.0.2.31"
BASE = f"http://{HOST}:8765"
TOKEN = "0123456789abcdef0123456789abcdef"
DEVICE_ID = "a1b2c3d4e5f6ab12"

INFO = {"id": DEVICE_ID, "name": "Smart Clock 2 AB12", "model": "Lenovo CD-24502F", "version": "0.9.0", "api": 3}
DOCK = {"dock": True, "light": 3, "phone_present": True, "phone_charging": False, "charger_state": 2, "raw": ""}
STATUS = {
    "mic_anchor": "running", "mic_muted": False, "dock_service": True, "dock_mcu": "1", "dock_wireless": "2",
    "webview": "com.android.webview 157.0.8103.38", "kiosk_version": "2026.11.29", "webhook_configured": False,
    "kiosk_auto_update": True, "kiosk_update_result": "up to date", "kiosk_update_checked": 0,
}
SETTINGS = {
    "kioskclock_mic_indicator": "pill", "kioskclock_mic_color": "#F29900", "kioskclock_mic_size": None,
    "kioskclock_hold_volume_up": None, "kioskclock_hold_volume_down": "ha_event",
    "kioskclock_tap_single": None, "kioskclock_tap_double": "screen_toggle",
    "kioskclock_double_tap_ms": "700",
    "kioskclock_wake_light": "both", "kioskclock_wake_minutes": None, "kioskclock_wake_max": "4",
}


@pytest.fixture(autouse=True)
def auto_enable_custom_integrations(enable_custom_integrations):
    yield


@pytest.fixture
def mock_clock(aioclient_mock: AiohttpClientMocker) -> AiohttpClientMocker:
    aioclient_mock.get(f"{BASE}/api/info", json=INFO)
    aioclient_mock.get(f"{BASE}/api/dock", json=DOCK)
    aioclient_mock.get(f"{BASE}/api/status", json=STATUS)
    aioclient_mock.get(f"{BASE}/api/settings", json=SETTINGS)
    aioclient_mock.post(f"{BASE}/api/webhook", json={"url": "x"})
    aioclient_mock.post(f"{BASE}/api/dock/light", json={**DOCK, "light": 10})
    aioclient_mock.post(f"{BASE}/api/settings", json={})
    aioclient_mock.post(f"{BASE}/api/screen/off", json={"screen": "black"})
    aioclient_mock.post(f"{BASE}/api/gesture", json={"gesture": "hold_up", "action": "voice"})
    return aioclient_mock


@pytest.fixture
def config_entry() -> MockConfigEntry:
    return MockConfigEntry(
        domain=DOMAIN,
        title="Smart Clock 2 AB12",
        unique_id=DEVICE_ID,
        data={CONF_HOST: HOST, CONF_PORT: 8765, "token": TOKEN},
    )
