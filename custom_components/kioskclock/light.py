"""Dock night light (levels 0..10)."""

from __future__ import annotations

from typing import Any

from homeassistant.components.light import ATTR_BRIGHTNESS, ColorMode, LightEntity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import KioskClockConfigEntry
from .api import KioskClockError
from .entity import KioskClockEntity

PARALLEL_UPDATES = 1
MAX_LEVEL = 10


def level_to_brightness(level: int) -> int:
    return round(level * 255 / MAX_LEVEL)


def brightness_to_level(brightness: int) -> int:
    return max(1, min(MAX_LEVEL, round(brightness * MAX_LEVEL / 255)))


async def async_setup_entry(
    hass: HomeAssistant, entry: KioskClockConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([DockLight(entry.runtime_data.coordinator, "dock_light")])


class DockLight(KioskClockEntity, LightEntity):
    _attr_color_mode = ColorMode.BRIGHTNESS
    _attr_supported_color_modes = {ColorMode.BRIGHTNESS}
    _last_level = MAX_LEVEL

    @property
    def available(self) -> bool:
        return super().available and bool(self.coordinator.data.dock.get("dock"))

    @property
    def is_on(self) -> bool:
        return self.coordinator.data.light_level > 0

    @property
    def brightness(self) -> int:
        level = self.coordinator.data.light_level
        if level:
            self._last_level = level
        return level_to_brightness(level)

    async def async_turn_on(self, **kwargs: Any) -> None:
        level = brightness_to_level(kwargs[ATTR_BRIGHTNESS]) if ATTR_BRIGHTNESS in kwargs else self._last_level
        await self._set(level)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._set(0)

    async def _set(self, level: int) -> None:
        try:
            dock = await self.coordinator.api.set_light(level)
        except KioskClockError as err:
            raise HomeAssistantError(f"Could not set the dock light: {err}") from err
        self.coordinator.set_dock(dock)
