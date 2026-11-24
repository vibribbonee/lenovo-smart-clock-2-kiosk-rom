"""Dock, phone and mic switch state."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
    BinarySensorEntityDescription,
)
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import KioskClockConfigEntry
from .coordinator import ClockData
from .entity import KioskClockEntity

PARALLEL_UPDATES = 0


@dataclass(frozen=True, kw_only=True)
class ClockBinarySensorDescription(BinarySensorEntityDescription):
    value: Callable[[ClockData], bool | None]
    needs_dock: bool = False


SENSORS = (
    ClockBinarySensorDescription(
        key="docked", device_class=BinarySensorDeviceClass.PLUG, value=lambda d: bool(d.dock.get("dock"))
    ),
    ClockBinarySensorDescription(
        key="phone_present", needs_dock=True, value=lambda d: bool(d.dock.get("phone_present"))
    ),
    ClockBinarySensorDescription(
        key="phone_charging",
        device_class=BinarySensorDeviceClass.BATTERY_CHARGING,
        needs_dock=True,
        value=lambda d: bool(d.dock.get("phone_charging")),
    ),
    ClockBinarySensorDescription(key="mic_muted", value=lambda d: d.status.get("mic_muted")),
)


async def async_setup_entry(
    hass: HomeAssistant, entry: KioskClockConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    coordinator = entry.runtime_data.coordinator
    async_add_entities(ClockBinarySensor(coordinator, d) for d in SENSORS)


class ClockBinarySensor(KioskClockEntity, BinarySensorEntity):
    entity_description: ClockBinarySensorDescription

    def __init__(self, coordinator, description: ClockBinarySensorDescription) -> None:
        super().__init__(coordinator, description.key)
        self.entity_description = description

    @property
    def available(self) -> bool:
        if not super().available:
            return False
        return not self.entity_description.needs_dock or bool(self.coordinator.data.dock.get("dock"))

    @property
    def is_on(self) -> bool | None:
        return self.entity_description.value(self.coordinator.data)
