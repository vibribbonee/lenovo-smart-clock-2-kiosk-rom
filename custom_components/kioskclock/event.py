"""Event entities for what the clock pushes to the webhook: taps, button holds, mic switch."""

from __future__ import annotations

from homeassistant.components.event import EventEntity, EventEntityDescription
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import KioskClockConfigEntry
from .const import SIGNAL_EVENT
from .entity import KioskClockEntity

PARALLEL_UPDATES = 0

# entity key -> (clock event type, event types)
EVENTS = {
    "tap": ("tap", ["single", "double"]),
    "button": ("button", ["hold_up", "hold_down"]),
    "mic_switch": ("mic", ["muted", "unmuted", "changed"]),
}


async def async_setup_entry(
    hass: HomeAssistant, entry: KioskClockConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    coordinator = entry.runtime_data.coordinator
    async_add_entities(ClockEvent(coordinator, key, clock_type, types) for key, (clock_type, types) in EVENTS.items())


class ClockEvent(KioskClockEntity, EventEntity):
    def __init__(self, coordinator, key: str, clock_type: str, event_types: list[str]) -> None:
        super().__init__(coordinator, key)
        self.entity_description = EventEntityDescription(key=key, event_types=event_types)
        self._clock_type = clock_type

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, SIGNAL_EVENT.format(self.coordinator.config_entry.entry_id), self._handle_clock_event
            )
        )

    @callback
    def _handle_clock_event(self, clock_type: str, value: str) -> None:
        if clock_type != self._clock_type:
            return
        event_type, _, action = value.partition(":")  # button: "hold_up:voice"; tap: "single:ringing"
        if event_type not in self.event_types:
            return
        if clock_type == "tap":
            attrs = {"ringing": action == "ringing"}
        else:
            attrs = {"action": action} if action else None
        self._trigger_event(event_type, attrs)
        self.async_write_ha_state()
