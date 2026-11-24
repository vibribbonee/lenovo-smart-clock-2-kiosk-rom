"""Base entity: one device per clock."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import KioskClockCoordinator


def device_info(coordinator: KioskClockCoordinator) -> DeviceInfo:
    entry = coordinator.config_entry
    return DeviceInfo(
        identifiers={(DOMAIN, entry.unique_id or entry.entry_id)},
        name=entry.title,
        manufacturer="Lenovo",
        model="Smart Clock 2 (kiosk ROM)",
        model_id=coordinator.info.get("model"),
        sw_version=coordinator.info.get("version"),
    )


class KioskClockEntity(CoordinatorEntity[KioskClockCoordinator]):
    _attr_has_entity_name = True

    def __init__(self, coordinator: KioskClockCoordinator, key: str) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{coordinator.config_entry.unique_id or coordinator.config_entry.entry_id}_{key}"
        self._attr_translation_key = key
        self._attr_device_info = device_info(coordinator)
