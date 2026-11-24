"""Wake-up light for Home Assistant lights: the clock says when its fade starts, we fade the lights.

ClockBridge owns the schedule (it knows the next Kiosk Satellite alarm, even without HA) and pushes
wake_light = start:SECONDS_UNTIL_ALARM or cancel. The lights come from the integration's options.
"""

from __future__ import annotations

import asyncio
import time

from homeassistant.components.light import ATTR_BRIGHTNESS_PCT, ATTR_TRANSITION, LightEntityFeature
from homeassistant.const import ATTR_ENTITY_ID, ATTR_SUPPORTED_FEATURES, STATE_OFF
from homeassistant.core import HomeAssistant, callback

from .const import CONF_WAKE_LIGHTS, CONF_WAKE_MAX_PCT, LOGGER

STEP_S = 15


class WakeLightRunner:
    def __init__(self, hass: HomeAssistant, entry) -> None:
        self.hass = hass
        self.entry = entry
        self._task: asyncio.Task | None = None
        self._was_off: set[str] = set()

    @callback
    def handle(self, value: str) -> None:
        if value.startswith("start:"):
            try:
                seconds = max(1, int(value[6:]))
            except ValueError:
                return
            self.cancel(restore=False)
            lights = list(self.entry.options.get(CONF_WAKE_LIGHTS) or [])
            if not lights:
                return
            self._task = self.entry.async_create_background_task(
                self.hass, self._fade(lights, seconds), "kioskclock wake-up light"
            )
        elif value == "cancel":
            self.cancel(restore=True)

    @callback
    def cancel(self, restore: bool = False) -> None:
        if self._task is not None and not self._task.done():
            self._task.cancel()
            if restore and self._was_off:
                self.hass.async_create_task(
                    self.hass.services.async_call("light", "turn_off", {ATTR_ENTITY_ID: sorted(self._was_off)})
                )
        self._task = None

    async def _fade(self, lights: list[str], seconds: int) -> None:
        max_pct = int(self.entry.options.get(CONF_WAKE_MAX_PCT, 100))
        # Lights that were off before the fade: turned off again if the alarm is cancelled.
        self._was_off = {e for e in lights if (s := self.hass.states.get(e)) is not None and s.state == STATE_OFF}
        sent: set[str] = set()
        active = list(lights)
        start = time.monotonic()
        LOGGER.debug("Wake-up light: fading %s to %s%% over %s s", lights, max_pct, seconds)
        while active:
            elapsed = time.monotonic() - start
            frac = min(1.0, elapsed / seconds)
            pct = max(1, round(max_pct * frac))
            for entity_id in list(active):
                state = self.hass.states.get(entity_id)
                # Gone, or switched off by someone after we turned it on: leave it alone.
                if state is None or (entity_id in sent and state.state == STATE_OFF):
                    active.remove(entity_id)
                    continue
                data = {ATTR_ENTITY_ID: entity_id, ATTR_BRIGHTNESS_PCT: pct}
                if frac < 1 and int(state.attributes.get(ATTR_SUPPORTED_FEATURES, 0)) & LightEntityFeature.TRANSITION:
                    data[ATTR_TRANSITION] = STEP_S
                await self.hass.services.async_call("light", "turn_on", data, blocking=True)
                sent.add(entity_id)
            if frac >= 1:
                break
            await asyncio.sleep(min(STEP_S, max(1.0, seconds - elapsed)))
        self._was_off = set()
