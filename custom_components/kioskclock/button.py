"""Screen on/off, the Kiosk Satellite update check, and running a gesture's action from HA."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from homeassistant.components.button import ButtonEntity, ButtonEntityDescription
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import KioskClockConfigEntry
from .api import KioskClockApi, KioskClockError
from .const import API_GESTURES, GESTURES
from .entity import KioskClockEntity

PARALLEL_UPDATES = 1


@dataclass(frozen=True, kw_only=True)
class ClockButtonDescription(ButtonEntityDescription):
    press: Callable[[KioskClockApi], Awaitable[Any]]


BUTTONS = (
    ClockButtonDescription(key="screen_on", press=lambda api: api.screen(True)),
    ClockButtonDescription(key="screen_off", press=lambda api: api.screen(False)),
    ClockButtonDescription(
        key="check_update", entity_category=EntityCategory.CONFIG, press=lambda api: api.check_update()
    ),
)


async def async_setup_entry(
    hass: HomeAssistant, entry: KioskClockConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    coordinator = entry.runtime_data.coordinator
    buttons = list(BUTTONS)
    if coordinator.api_revision >= API_GESTURES:
        buttons += [_gesture_button(g) for g in GESTURES]
    async_add_entities(ClockButton(coordinator, d) for d in buttons)


def _gesture_button(gesture: str) -> ClockButtonDescription:
    """Runs whatever the gesture is set to on the clock, as if it was done there (not echoed back as an event)."""
    return ClockButtonDescription(key=f"run_{gesture}", press=lambda api: api.run_gesture(gesture))


class ClockButton(KioskClockEntity, ButtonEntity):
    entity_description: ClockButtonDescription

    def __init__(self, coordinator, description: ClockButtonDescription) -> None:
        super().__init__(coordinator, description.key)
        self.entity_description = description

    async def async_press(self) -> None:
        try:
            await self.entity_description.press(self.coordinator.api)
        except KioskClockError as err:
            raise HomeAssistantError(f"The clock did not accept the command: {err}") from err
        if self.entity_description.key == "check_update":
            # The check runs in the background on the clock; pick up the result a bit later.
            self.hass.loop.call_later(20, lambda: self.hass.async_create_task(self.coordinator.async_request_refresh()))
