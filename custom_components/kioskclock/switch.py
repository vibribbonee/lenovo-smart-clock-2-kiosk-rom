"""On/off ClockBridge settings: automatic app updates, ClockCore's mic anchor."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from homeassistant.components.switch import SwitchEntity, SwitchEntityDescription
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import KioskClockConfigEntry
from .api import KioskClockError
from .entity import KioskClockEntity

PARALLEL_UPDATES = 1


@dataclass(frozen=True, kw_only=True)
class ClockSwitchDescription(SwitchEntityDescription):
    setting: str


SWITCHES = (
    # Newer ClockBridge / KioskNav from this project's GitHub releases (unset = on).
    ClockSwitchDescription(key="app_updates", setting="kioskclock_app_updates", entity_category=EntityCategory.CONFIG),
    # ClockCore holds the mic at 48 kHz for the MT8167 audio bug; off once Kiosk Satellite records at 48 kHz itself.
    ClockSwitchDescription(key="mic_anchor", setting="kioskclock_mic_anchor", entity_category=EntityCategory.CONFIG,
                           entity_registry_enabled_default=False),
)


async def async_setup_entry(
    hass: HomeAssistant, entry: KioskClockConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    coordinator = entry.runtime_data.coordinator
    async_add_entities(ClockSwitch(coordinator, d) for d in SWITCHES if d.setting in coordinator.data.settings)


class ClockSwitch(KioskClockEntity, SwitchEntity):
    entity_description: ClockSwitchDescription

    def __init__(self, coordinator, description: ClockSwitchDescription) -> None:
        super().__init__(coordinator, description.key)
        self.entity_description = description

    @property
    def is_on(self) -> bool:
        return self.coordinator.data.settings.get(self.entity_description.setting) != "0"

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._set("1")

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._set("0")

    async def _set(self, value: str) -> None:
        desc = self.entity_description
        try:
            await self.coordinator.api.set_setting(desc.setting, value)
        except KioskClockError as err:
            raise HomeAssistantError(f"The clock did not accept it: {err}") from err
        self.coordinator.set_setting(desc.setting, value)
