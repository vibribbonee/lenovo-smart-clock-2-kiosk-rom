"""ClockBridge settings (/api/settings) as selects."""

from __future__ import annotations

from dataclasses import dataclass

from homeassistant.components.select import SelectEntity, SelectEntityDescription
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import KioskClockConfigEntry
from .api import KioskClockError
from .const import (
    DEFAULT_VALUE,
    DOUBLE_TAP_SPEEDS,
    GESTURE_ACTIONS,
    MIC_COLORS,
    MIC_INDICATORS,
    MIC_SIZES,
    SETTING_HOLD_DOWN,
    SETTING_HOLD_UP,
    SETTING_MIC_COLOR,
    SETTING_MIC_INDICATOR,
    SETTING_MIC_SIZE,
    SETTING_DOUBLE_TAP_MS,
    SETTING_TAP_DOUBLE,
    SETTING_TAP_SINGLE,
)
from .entity import KioskClockEntity

PARALLEL_UPDATES = 1


@dataclass(frozen=True, kw_only=True)
class ClockSelectDescription(SelectEntityDescription):
    setting: str
    # option -> value stored on the clock; None = the clock's default (setting unset)
    values: dict[str, str | None]
    default_option: str


def _identity(options: list[str]) -> dict[str, str | None]:
    return {o: o for o in options}


SELECTS = (
    ClockSelectDescription(
        key="mic_indicator", setting=SETTING_MIC_INDICATOR, values=_identity(MIC_INDICATORS), default_option="dot"
    ),
    ClockSelectDescription(key="mic_color", setting=SETTING_MIC_COLOR, values=dict(MIC_COLORS), default_option="red"),
    ClockSelectDescription(key="mic_size", setting=SETTING_MIC_SIZE, values=dict(MIC_SIZES), default_option="default"),
    ClockSelectDescription(
        key="hold_volume_up", setting=SETTING_HOLD_UP, values=_identity(GESTURE_ACTIONS), default_option="voice"
    ),
    ClockSelectDescription(
        key="hold_volume_down", setting=SETTING_HOLD_DOWN, values=_identity(GESTURE_ACTIONS), default_option="dock_light"
    ),
    ClockSelectDescription(
        key="tap_single", setting=SETTING_TAP_SINGLE, values=_identity(GESTURE_ACTIONS), default_option="alarm_snooze"
    ),
    ClockSelectDescription(
        key="tap_double", setting=SETTING_TAP_DOUBLE, values=_identity(GESTURE_ACTIONS), default_option="alarm_stop"
    ),
    ClockSelectDescription(
        key="double_tap_speed", setting=SETTING_DOUBLE_TAP_MS, values=dict(DOUBLE_TAP_SPEEDS), default_option="normal"
    ),
)


async def async_setup_entry(
    hass: HomeAssistant, entry: KioskClockConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    coordinator = entry.runtime_data.coordinator
    # Only the settings this ClockBridge knows (the tap ones arrived with API revision 3).
    async_add_entities(ClockSelect(coordinator, d) for d in SELECTS if d.setting in coordinator.data.settings)


class ClockSelect(KioskClockEntity, SelectEntity):
    entity_description: ClockSelectDescription
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator, description: ClockSelectDescription) -> None:
        super().__init__(coordinator, description.key)
        self.entity_description = description
        self._attr_options = list(description.values)

    @property
    def current_option(self) -> str | None:
        desc = self.entity_description
        value = self.coordinator.data.settings.get(desc.setting)
        if value is None:
            return desc.default_option
        for option, stored in desc.values.items():
            if stored is not None and stored.lower() == str(value).lower():
                return option
        return None  # a custom value set on the clock (e.g. another colour via adb)

    async def async_select_option(self, option: str) -> None:
        desc = self.entity_description
        stored = desc.values[option]
        try:
            await self.coordinator.api.set_setting(desc.setting, DEFAULT_VALUE if stored is None else stored)
        except KioskClockError as err:
            raise HomeAssistantError(f"The clock did not accept {option}: {err}") from err
        self.coordinator.set_setting(desc.setting, stored)
