"""Wake-up light fade length and dock light limit (ClockBridge settings)."""

from __future__ import annotations

from dataclasses import dataclass

from homeassistant.components.number import NumberEntity, NumberEntityDescription, NumberMode
from homeassistant.const import EntityCategory, UnitOfTime
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import KioskClockConfigEntry
from .api import KioskClockError
from .const import SETTING_WAKE_MAX, SETTING_WAKE_MINUTES
from .entity import KioskClockEntity

PARALLEL_UPDATES = 1


@dataclass(frozen=True, kw_only=True)
class ClockNumberDescription(NumberEntityDescription):
    setting: str
    default: int


NUMBERS = (
    ClockNumberDescription(
        key="wake_minutes", setting=SETTING_WAKE_MINUTES, default=20, native_min_value=5, native_max_value=60,
        native_step=5, native_unit_of_measurement=UnitOfTime.MINUTES, mode=NumberMode.SLIDER,
    ),
    ClockNumberDescription(
        key="wake_max", setting=SETTING_WAKE_MAX, default=10, native_min_value=1, native_max_value=10,
        native_step=1, mode=NumberMode.SLIDER,
    ),
)


async def async_setup_entry(
    hass: HomeAssistant, entry: KioskClockConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    coordinator = entry.runtime_data.coordinator
    # Wake-up light settings arrived with ClockBridge 0.9.1; older ones don't list them.
    async_add_entities(ClockNumber(coordinator, d) for d in NUMBERS if d.setting in coordinator.data.settings)


class ClockNumber(KioskClockEntity, NumberEntity):
    entity_description: ClockNumberDescription
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator, description: ClockNumberDescription) -> None:
        super().__init__(coordinator, description.key)
        self.entity_description = description

    @property
    def native_value(self) -> float:
        value = self.coordinator.data.settings.get(self.entity_description.setting)
        try:
            return float(value) if value is not None else float(self.entity_description.default)
        except (TypeError, ValueError):
            return float(self.entity_description.default)

    async def async_set_native_value(self, value: float) -> None:
        desc = self.entity_description
        stored = str(int(value))
        try:
            await self.coordinator.api.set_setting(desc.setting, stored)
        except KioskClockError as err:
            raise HomeAssistantError(f"The clock did not accept {stored}: {err}") from err
        self.coordinator.set_setting(desc.setting, stored)
