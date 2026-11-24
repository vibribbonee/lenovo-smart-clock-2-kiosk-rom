"""Kiosk Clock: the Lenovo Smart Clock 2 running the kiosk ROM, via its ClockBridge API."""

from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Any

from aiohttp import web

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT, Platform
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.network import NoURLAvailableError

from .api import KioskClockApi, KioskClockError, NotSupported
from .const import CONF_TOKEN, CONF_WEBHOOK_ID, DOMAIN, EVENT_KIOSKCLOCK, LOGGER, SIGNAL_EVENT
from .coordinator import KioskClockCoordinator
from .wake import WakeLightRunner

PLATFORMS = [
    Platform.BINARY_SENSOR,
    Platform.BUTTON,
    Platform.EVENT,
    Platform.LIGHT,
    Platform.NUMBER,
    Platform.SELECT,
    Platform.SENSOR,
]

# Clock events that change polled state: refresh right away instead of waiting for the next poll.
REFRESH_ON = {"dock", "phone", "mic"}


@dataclass
class KioskClockRuntime:
    api: KioskClockApi
    coordinator: KioskClockCoordinator
    push: bool = False  # the clock accepted our webhook (ClockBridge 0.9.0+)
    wake: WakeLightRunner | None = None


type KioskClockConfigEntry = ConfigEntry[KioskClockRuntime]


def _api(hass: HomeAssistant, entry: ConfigEntry) -> KioskClockApi:
    return KioskClockApi(
        async_get_clientsession(hass), entry.data[CONF_HOST], entry.data[CONF_PORT], entry.data[CONF_TOKEN]
    )


async def async_setup_entry(hass: HomeAssistant, entry: KioskClockConfigEntry) -> bool:
    api = _api(hass, entry)
    coordinator = KioskClockCoordinator(hass, entry, api)
    await coordinator.async_config_entry_first_refresh()
    try:
        coordinator.info = await api.info()
    except KioskClockError:
        coordinator.info = {}  # ClockBridge before 0.9.0
    _migrate_unique_id(hass, entry, coordinator.info.get("id"))

    entry.runtime_data = KioskClockRuntime(api, coordinator, wake=WakeLightRunner(hass, entry))
    entry.async_on_unload(entry.runtime_data.wake.cancel)
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    await _async_setup_webhook(hass, entry)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: KioskClockConfigEntry) -> bool:
    if CONF_WEBHOOK_ID in entry.data:
        webhook.async_unregister(hass, entry.data[CONF_WEBHOOK_ID])
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Stop the clock from pushing events to a webhook that no longer exists."""
    try:
        await _api(hass, entry).set_webhook("")
    except KioskClockError:
        pass


@callback
def _migrate_unique_id(hass: HomeAssistant, entry: ConfigEntry, device_id: str | None) -> None:
    """Entries added by address on an old ClockBridge use host:port; switch to the device id once known."""
    if not device_id or entry.unique_id == device_id:
        return
    if hass.config_entries.async_entry_for_domain_unique_id(DOMAIN, device_id):
        return
    if entry.unique_id and ":" not in entry.unique_id:
        return
    old = entry.unique_id or entry.entry_id
    hass.config_entries.async_update_entry(entry, unique_id=device_id)
    # Carry entities and the device over to the new id.
    from homeassistant.helpers import entity_registry as er

    ent_reg = er.async_get(hass)
    for ent in er.async_entries_for_config_entry(ent_reg, entry.entry_id):
        if ent.unique_id.startswith(f"{old}_"):
            ent_reg.async_update_entity(ent.entity_id, new_unique_id=device_id + ent.unique_id[len(old):])
    dev_reg = dr.async_get(hass)
    for device in dr.async_entries_for_config_entry(dev_reg, entry.entry_id):
        if (DOMAIN, old) in device.identifiers:
            dev_reg.async_update_device(device.id, new_identifiers={(DOMAIN, device_id)})


async def _async_setup_webhook(hass: HomeAssistant, entry: KioskClockConfigEntry) -> None:
    webhook_id = entry.data.get(CONF_WEBHOOK_ID)
    if not webhook_id:
        webhook_id = webhook.async_generate_id()
        hass.config_entries.async_update_entry(entry, data={**entry.data, CONF_WEBHOOK_ID: webhook_id})
    webhook.async_register(
        hass, DOMAIN, f"Kiosk Clock {entry.title}", webhook_id, _handle_webhook, local_only=True,
        allowed_methods=["POST"],
    )
    try:
        url = webhook.async_generate_url(hass, webhook_id, prefer_external=False)
    except NoURLAvailableError:
        LOGGER.warning("No Home Assistant URL known; the clock's events (taps, buttons) won't arrive. "
                       "Set one under Settings → System → Network")
        return
    try:
        await entry.runtime_data.api.set_webhook(url)
    except NotSupported:
        LOGGER.warning("ClockBridge on %s is too old for push events (needs the 0.9.0 ROM update); "
                       "polling only", entry.title)
        return
    except KioskClockError as err:
        LOGGER.warning("Could not register the webhook on %s: %s", entry.title, err)
        return
    entry.runtime_data.push = True
    LOGGER.debug("Clock %s pushes events to %s", entry.title, url)


def _entry_for_webhook(hass: HomeAssistant, webhook_id: str) -> KioskClockConfigEntry | None:
    for entry in hass.config_entries.async_loaded_entries(DOMAIN):
        if entry.data.get(CONF_WEBHOOK_ID) == webhook_id:
            return entry
    return None


def trigger_for(event_type: str, value: str) -> str:
    """Device-trigger name for a clock event: tap single -> tap_single, button hold_up:voice -> hold_up.
    Taps while an alarm rings arrive as single:ringing and fire the same tap_single trigger."""
    if event_type == "button":
        return value.partition(":")[0]
    if event_type == "tap":
        return f"tap_{value.partition(':')[0]}"
    return f"{event_type}_{value}"


async def _handle_webhook(hass: HomeAssistant, webhook_id: str, request: web.Request) -> web.Response:
    entry = _entry_for_webhook(hass, webhook_id)
    if entry is None:
        return web.Response(status=404)
    try:
        body: Any = await request.json()
    except (ValueError, json.JSONDecodeError):
        return web.Response(status=400)
    if not isinstance(body, dict) or not isinstance(body.get("type"), str):
        return web.Response(status=400)
    event_type, value = body["type"], str(body.get("value", ""))
    devices = dr.async_entries_for_config_entry(dr.async_get(hass), entry.entry_id)
    hass.bus.async_fire(
        EVENT_KIOSKCLOCK,
        {
            "device_id": devices[0].id if devices else None,
            "type": event_type,
            "value": value,
            "trigger": trigger_for(event_type, value),
            "ringing": event_type == "tap" and value.endswith(":ringing"),
        },
    )
    async_dispatcher_send(hass, SIGNAL_EVENT.format(entry.entry_id), event_type, value)
    if event_type == "wake_light" and entry.runtime_data.wake is not None:
        entry.runtime_data.wake.handle(value)
    if event_type in REFRESH_ON:
        await entry.runtime_data.coordinator.async_request_refresh()
    return web.Response(status=200)
