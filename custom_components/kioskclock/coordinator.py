"""Polls the clock; webhook events trigger an early refresh."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import timedelta
from typing import TYPE_CHECKING, Any

from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .api import InvalidAuth, KioskClockApi, KioskClockError
from .const import DOMAIN, LOGGER

if TYPE_CHECKING:
    from . import KioskClockConfigEntry


@dataclass
class ClockData:
    dock: dict[str, Any] = field(default_factory=dict)
    status: dict[str, Any] = field(default_factory=dict)
    settings: dict[str, Any] = field(default_factory=dict)

    @property
    def light_level(self) -> int:
        # The dock MCU reports 11 for level 10.
        return max(0, min(10, int(self.dock.get("light") or 0)))


class KioskClockCoordinator(DataUpdateCoordinator[ClockData]):
    config_entry: KioskClockConfigEntry

    def __init__(self, hass: HomeAssistant, entry: KioskClockConfigEntry, api: KioskClockApi) -> None:
        super().__init__(hass, LOGGER, config_entry=entry, name=DOMAIN, update_interval=timedelta(seconds=15))
        self.api = api
        self.info: dict[str, Any] = {}

    @property
    def api_revision(self) -> int:
        try:
            return int(self.info.get("api") or 0)
        except (TypeError, ValueError):
            return 0

    async def _async_update_data(self) -> ClockData:
        try:
            return ClockData(
                dock=await self.api.dock(),
                status=await self.api.status(),
                settings=await self.api.settings(),
            )
        except InvalidAuth as err:
            raise ConfigEntryAuthFailed("The clock rejected the API token") from err
        except KioskClockError as err:
            raise UpdateFailed(str(err)) from err

    def set_dock(self, dock: dict[str, Any]) -> None:
        """Apply a dock state returned by a command without waiting for the next poll."""
        if self.data is not None and "light" in dock:
            self.async_set_updated_data(ClockData(dock=dock, status=self.data.status, settings=self.data.settings))

    def set_setting(self, key: str, value: str | None) -> None:
        if self.data is not None:
            settings = {**self.data.settings, key: value}
            self.async_set_updated_data(ClockData(dock=self.data.dock, status=self.data.status, settings=settings))
