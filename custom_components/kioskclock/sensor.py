"""Versions and the Kiosk Satellite update state (diagnostic)."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from homeassistant.components.sensor import SensorEntity, SensorEntityDescription
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import KioskClockConfigEntry
from .coordinator import ClockData
from .entity import KioskClockEntity

PARALLEL_UPDATES = 0


def _str(value: Any) -> str | None:
    return None if value in (None, "", "null", "none") else str(value)


@dataclass(frozen=True, kw_only=True)
class ClockSensorDescription(SensorEntityDescription):
    value: Callable[[ClockData], str | None]


SENSORS = (
    ClockSensorDescription(key="kiosk_version", value=lambda d: _str(d.status.get("kiosk_version"))),
    ClockSensorDescription(key="webview_version", value=lambda d: _str(d.status.get("webview"))),
    ClockSensorDescription(key="kiosk_update_result", value=lambda d: _str(d.status.get("kiosk_update_result"))),
)


async def async_setup_entry(
    hass: HomeAssistant, entry: KioskClockConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    coordinator = entry.runtime_data.coordinator
    async_add_entities(ClockSensor(coordinator, d) for d in SENSORS)


class ClockSensor(KioskClockEntity, SensorEntity):
    entity_description: ClockSensorDescription
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, coordinator, description: ClockSensorDescription) -> None:
        super().__init__(coordinator, description.key)
        self.entity_description = description

    @property
    def native_value(self) -> str | None:
        return self.entity_description.value(self.coordinator.data)
