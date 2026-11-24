"""Diagnostics never contain the token or the webhook id."""

from __future__ import annotations

from homeassistant.core import HomeAssistant

from custom_components.kioskclock.diagnostics import async_get_config_entry_diagnostics

from .conftest import TOKEN
from .test_init import _setup


async def test_diagnostics_redacted(hass: HomeAssistant, mock_clock, config_entry) -> None:
    await _setup(hass, config_entry)
    diag = await async_get_config_entry_diagnostics(hass, config_entry)
    text = repr(diag)
    assert TOKEN not in text
    assert config_entry.data["webhook_id"] not in text
    assert diag["status"]["bridge_version"] == "1.0.0"
    assert diag["info"]["id"] == "**REDACTED**"
