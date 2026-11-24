"""Update entity: the ROM's own apps (ClockBridge, KioskNav) against this project's GitHub releases.

Installing just tells the clock to check now (POST /api/update/check); the clock downloads,
verifies (pinned signing certificate, never downgrades) and installs by itself, so this works the
same with or without Home Assistant.
"""

from __future__ import annotations

import asyncio

from datetime import timedelta
from typing import Any

from homeassistant.components.update import UpdateDeviceClass, UpdateEntity, UpdateEntityFeature
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity, DataUpdateCoordinator, UpdateFailed

from . import KioskClockConfigEntry
from .api import KioskClockError
from .const import DOMAIN, LOGGER
from .entity import KioskClockEntity

PARALLEL_UPDATES = 1
RELEASES = "https://api.github.com/repos/vibribbonee/lenovo-smart-clock-2-kiosk-rom/releases/latest"
INSTALL_WAIT_S = 180
INSTALL_POLL_S = 10


async def async_setup_entry(
    hass: HomeAssistant, entry: KioskClockConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    coordinator = entry.runtime_data.coordinator
    # Only ClockBridge 1.0.0+ reports its version and updates itself.
    if not coordinator.data.status.get("bridge_version"):
        return
    releases = hass.data.setdefault(DOMAIN, {}).get("releases")
    if releases is None:
        releases = hass.data[DOMAIN]["releases"] = ReleaseCoordinator(hass)
    await releases.async_refresh()
    async_add_entities([AppsUpdate(coordinator, releases)])


class ReleaseCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Latest published release (drafts and pre-releases excluded), shared by all clocks."""

    def __init__(self, hass: HomeAssistant) -> None:
        super().__init__(hass, LOGGER, config_entry=None, name=f"{DOMAIN} releases", update_interval=timedelta(hours=6))

    async def _async_update_data(self) -> dict[str, Any]:
        session = async_get_clientsession(self.hass)
        try:
            async with session.get(RELEASES, headers={"Accept": "application/vnd.github+json"}, timeout=20) as resp:
                if resp.status == 404:
                    return {}  # no release published yet
                resp.raise_for_status()
                return await resp.json()
        except Exception as err:  # noqa: BLE001 - any network problem just means "unknown for now"
            raise UpdateFailed(f"GitHub releases: {err}") from err


class AppsUpdate(KioskClockEntity, UpdateEntity):
    _attr_device_class = UpdateDeviceClass.FIRMWARE
    _attr_supported_features = UpdateEntityFeature.INSTALL | UpdateEntityFeature.RELEASE_NOTES
    _attr_title = "Kiosk Clock apps"

    def __init__(self, coordinator, releases: ReleaseCoordinator) -> None:
        super().__init__(coordinator, "apps_update")
        self._releases = releases

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.async_on_remove(self._releases.async_add_listener(self._handle_coordinator_update))

    @property
    def installed_version(self) -> str | None:
        return self.coordinator.data.status.get("bridge_version")

    @property
    def latest_version(self) -> str | None:
        tag = (self._releases.data or {}).get("tag_name")
        if not tag:
            return self.installed_version
        return str(tag).removeprefix("v")

    @property
    def release_url(self) -> str | None:
        return (self._releases.data or {}).get("html_url")

    async def async_release_notes(self) -> str | None:
        return (self._releases.data or {}).get("body")

    async def async_install(self, version: str | None, backup: bool, **kwargs: Any) -> None:
        """Ask the clock to update, then wait (HA shows "installing") until it reports a new version."""
        before = self.installed_version
        try:
            await self.coordinator.api.check_update()
        except KioskClockError as err:
            raise HomeAssistantError(f"The clock did not start the update: {err}") from err
        loop = asyncio.get_running_loop()
        deadline = loop.time() + INSTALL_WAIT_S
        while loop.time() < deadline:
            await asyncio.sleep(INSTALL_POLL_S)
            await self.coordinator.async_refresh()
            if self.installed_version != before:
                return
        LOGGER.info("No new ClockBridge version after %s s (already current, or still installing)", INSTALL_WAIT_S)
