"""Diagnostics download (Settings → Devices → the clock → ⋮ → Download diagnostics) for bug reports."""

from __future__ import annotations

from typing import Any

from homeassistant.components.diagnostics import async_redact_data
from homeassistant.core import HomeAssistant

from . import KioskClockConfigEntry
from .const import CONF_TOKEN, CONF_WAKE_LIGHTS, CONF_WEBHOOK_ID

TO_REDACT = {CONF_TOKEN, CONF_WEBHOOK_ID, "id", "url"}


async def async_get_config_entry_diagnostics(hass: HomeAssistant, entry: KioskClockConfigEntry) -> dict[str, Any]:
    rt = entry.runtime_data
    data = rt.coordinator.data
    return {
        "entry": async_redact_data(dict(entry.data), TO_REDACT),
        "options": {**entry.options, CONF_WAKE_LIGHTS: len(entry.options.get(CONF_WAKE_LIGHTS) or [])},
        "push": rt.push,
        "api_revision": rt.coordinator.api_revision,
        "info": async_redact_data(rt.coordinator.info, TO_REDACT),
        "dock": data.dock if data else None,
        "status": data.status if data else None,
        "settings": data.settings if data else None,
        "last_update_success": rt.coordinator.last_update_success,
    }
