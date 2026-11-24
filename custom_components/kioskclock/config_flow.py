"""Config flow: zeroconf discovery (_kioskclock._tcp) or an address, then pair on the clock or type the token."""

from __future__ import annotations

import asyncio
from collections.abc import Mapping
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import SOURCE_REAUTH, ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import callback
from homeassistant.helpers import selector
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

from .api import Busy, CannotConnect, InvalidAuth, KioskClockApi, KioskClockError, NotSupported, clean_token
from .const import CONF_TOKEN, CONF_WAKE_LIGHTS, CONF_WAKE_MAX_PCT, DEFAULT_NAME, DEFAULT_PORT, DOMAIN, LOGGER

TOKEN_SCHEMA = vol.Schema({vol.Required(CONF_TOKEN): str})
PAIR_POLL_S = 2
PAIR_TIMEOUT_S = 125
API_PAIRING = 4  # ClockBridge 0.9.3+


class KioskClockConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    def __init__(self) -> None:
        self._host: str | None = None
        self._port: int = DEFAULT_PORT
        self._name: str = DEFAULT_NAME
        self._can_pair: bool | None = None  # None = unknown (address entered by hand)
        self._pair_task: asyncio.Task | None = None
        self._pair_code: str = ""
        self._pair_token: str | None = None

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return KioskClockOptionsFlow()

    def _api(self, token: str = "") -> KioskClockApi:
        assert self._host is not None
        return KioskClockApi(async_get_clientsession(self.hass), self._host, self._port, token)

    async def _probe(self, token: str) -> tuple[dict[str, Any] | None, str | None]:
        """Returns (info, error key). info is {} on ClockBridge before 0.9.0 (no /api/info)."""
        api = self._api(token)
        try:
            try:
                return await api.info(), None
            except NotSupported:
                await api.status()
                return {}, None
        except InvalidAuth:
            return None, "invalid_auth"
        except CannotConnect:
            return None, "cannot_connect"
        except KioskClockError:
            LOGGER.exception("Unexpected answer from %s:%s", self._host, self._port)
            return None, "unknown"

    async def _finish(self, token: str, info: dict[str, Any]) -> ConfigFlowResult:
        data = {CONF_HOST: self._host, CONF_PORT: self._port, CONF_TOKEN: token}
        if self.source == SOURCE_REAUTH:
            return self.async_update_reload_and_abort(self._get_reauth_entry(), data_updates={CONF_TOKEN: token})
        if self.unique_id is None:
            await self.async_set_unique_id(info.get("id") or f"{self._host}:{self._port}")
            self._abort_if_unique_id_configured(updates=data)
        return self.async_create_entry(title=info.get("name") or self._name, data=data)

    # ---- discovery ------------------------------------------------------------------------------

    async def async_step_zeroconf(self, discovery_info: ZeroconfServiceInfo) -> ConfigFlowResult:
        if discovery_info.ip_address.version != 4:
            return self.async_abort(reason="not_ipv4")
        device_id = discovery_info.properties.get("id")
        if not device_id:
            return self.async_abort(reason="no_device_id")
        self._host = str(discovery_info.ip_address)
        self._port = discovery_info.port or DEFAULT_PORT
        self._name = discovery_info.name.split("._kioskclock", 1)[0] or DEFAULT_NAME
        try:
            self._can_pair = int(discovery_info.properties.get("api") or 0) >= API_PAIRING
        except ValueError:
            self._can_pair = False
        await self.async_set_unique_id(device_id)
        self._abort_if_unique_id_configured(updates={CONF_HOST: self._host, CONF_PORT: self._port})
        self.context["title_placeholders"] = {"name": self._name}
        return await self.async_step_method()

    async def async_step_method(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Pair on the clock (no typing) or enter the token."""
        if self._can_pair is False:
            return await self.async_step_token()
        return self.async_show_menu(
            step_id="method", menu_options=["pair", "token"],
            description_placeholders={"name": self._name, "host": self._host or ""},
        )

    # ---- pairing --------------------------------------------------------------------------------

    async def _wait_for_pair(self, api: KioskClockApi, pair_id: str) -> str | None:
        deadline = asyncio.get_running_loop().time() + PAIR_TIMEOUT_S
        while asyncio.get_running_loop().time() < deadline:
            await asyncio.sleep(PAIR_POLL_S)
            try:
                r = await api.pair_poll(pair_id)
            except KioskClockError:
                continue
            state = r.get("state")
            if state == "allowed" and r.get("token"):
                return str(r["token"])
            if state not in ("pending", None):
                return None
        return None

    async def async_step_pair(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if self._pair_task is None:
            api = self._api()
            try:
                r = await api.pair_start("Home Assistant")
            except NotSupported:
                self._can_pair = False
                return await self.async_step_token()
            except Busy:
                return self.async_abort(reason="pair_busy")
            except KioskClockError:
                return self.async_abort(reason="cannot_connect")
            self._pair_code = str(r.get("code", ""))
            self._pair_task = self.hass.async_create_task(self._wait_for_pair(api, str(r.get("id", ""))))
        if not self._pair_task.done():
            return self.async_show_progress(
                step_id="pair", progress_action="pair", progress_task=self._pair_task,
                description_placeholders={"code": " ".join(self._pair_code), "name": self._name},
            )
        try:
            self._pair_token = self._pair_task.result()
        except Exception:  # noqa: BLE001 - a failed wait just means "not paired"
            self._pair_token = None
        self._pair_task = None
        return self.async_show_progress_done(next_step_id="paired" if self._pair_token else "pair_failed")

    async def async_step_paired(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        assert self._pair_token is not None
        info, error = await self._probe(self._pair_token)
        if error or info is None:
            return self.async_abort(reason=error or "unknown")
        return await self._finish(self._pair_token, info)

    async def async_step_pair_failed(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        return self.async_show_menu(step_id="pair_failed", menu_options=["pair", "token"])

    # ---- token ----------------------------------------------------------------------------------

    async def async_step_token(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        assert self._host is not None
        errors: dict[str, str] = {}
        if user_input is not None:
            token = clean_token(user_input[CONF_TOKEN])
            info, error = await self._probe(token)
            if error:
                errors["base"] = error
            else:
                assert info is not None
                return await self._finish(token, info)
        return self.async_show_form(
            step_id="token", data_schema=TOKEN_SCHEMA, errors=errors,
            description_placeholders={"name": self._name, "host": self._host},
        )

    # ---- manual ---------------------------------------------------------------------------------

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            self._host = user_input[CONF_HOST].strip()
            self._port = user_input[CONF_PORT]
            token = clean_token(user_input.get(CONF_TOKEN) or "")
            if not token:
                return await self.async_step_method()
            info, error = await self._probe(token)
            if error:
                errors["base"] = error
            else:
                assert info is not None
                return await self._finish(token, info)
        schema = vol.Schema(
            {
                vol.Required(CONF_HOST): str,
                vol.Required(CONF_PORT, default=DEFAULT_PORT): vol.All(vol.Coerce(int), vol.Range(1, 65535)),
                vol.Optional(CONF_TOKEN): str,
            }
        )
        return self.async_show_form(
            step_id="user", data_schema=self.add_suggested_values_to_schema(schema, user_input), errors=errors
        )

    # ---- new token ------------------------------------------------------------------------------

    async def async_step_reauth(self, entry_data: Mapping[str, Any]) -> ConfigFlowResult:
        entry = self._get_reauth_entry()
        self._host, self._port, self._name = entry.data[CONF_HOST], entry.data[CONF_PORT], entry.title
        return self.async_show_menu(
            step_id="reauth", menu_options=["pair", "reauth_confirm"], description_placeholders={"name": entry.title}
        )

    async def async_step_reauth_confirm(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        entry = self._get_reauth_entry()
        if self._host is None:
            self._host, self._port = entry.data[CONF_HOST], entry.data[CONF_PORT]
        errors: dict[str, str] = {}
        if user_input is not None:
            token = clean_token(user_input[CONF_TOKEN])
            _, error = await self._probe(token)
            if error:
                errors["base"] = error
            else:
                return self.async_update_reload_and_abort(entry, data_updates={CONF_TOKEN: token})
        return self.async_show_form(
            step_id="reauth_confirm", data_schema=TOKEN_SCHEMA, errors=errors,
            description_placeholders={"name": entry.title},
        )


class KioskClockOptionsFlow(OptionsFlow):
    """Home Assistant lights for the wake-up light (the clock decides when; set it to HA or Both there)."""

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(data=user_input)
        schema = vol.Schema(
            {
                vol.Optional(CONF_WAKE_LIGHTS, default=[]): selector.EntitySelector(
                    selector.EntitySelectorConfig(domain="light", multiple=True)
                ),
                vol.Required(CONF_WAKE_MAX_PCT, default=100): selector.NumberSelector(
                    selector.NumberSelectorConfig(min=1, max=100, step=1, unit_of_measurement="%",
                                                  mode=selector.NumberSelectorMode.SLIDER)
                ),
            }
        )
        return self.async_show_form(
            step_id="init", data_schema=self.add_suggested_values_to_schema(schema, self.config_entry.options)
        )
