"""Config flow: zeroconf discovery (_kioskclock._tcp) or address entry, then the API token."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import callback
from homeassistant.helpers import selector
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

from .api import CannotConnect, InvalidAuth, KioskClockApi, KioskClockError, NotSupported, clean_token
from .const import CONF_TOKEN, CONF_WAKE_LIGHTS, CONF_WAKE_MAX_PCT, DEFAULT_NAME, DEFAULT_PORT, DOMAIN, LOGGER

TOKEN_SCHEMA = vol.Schema({vol.Required(CONF_TOKEN): str})


class KioskClockConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return KioskClockOptionsFlow()

    def __init__(self) -> None:
        self._host: str | None = None
        self._port: int = DEFAULT_PORT
        self._name: str = DEFAULT_NAME

    async def _probe(self, host: str, port: int, token: str) -> tuple[dict[str, Any] | None, str | None]:
        """Returns (info, error key). info is {} on ClockBridge before 0.9.0 (no /api/info)."""
        api = KioskClockApi(async_get_clientsession(self.hass), host, port, token)
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
            LOGGER.exception("Unexpected answer from %s:%s", host, port)
            return None, "unknown"

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
        await self.async_set_unique_id(device_id)
        self._abort_if_unique_id_configured(updates={CONF_HOST: self._host, CONF_PORT: self._port})
        self.context["title_placeholders"] = {"name": self._name}
        return await self.async_step_token()

    async def async_step_token(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        assert self._host is not None
        errors: dict[str, str] = {}
        if user_input is not None:
            token = clean_token(user_input[CONF_TOKEN])
            info, error = await self._probe(self._host, self._port, token)
            if error:
                errors["base"] = error
            else:
                assert info is not None
                return self.async_create_entry(
                    title=info.get("name") or self._name,
                    data={CONF_HOST: self._host, CONF_PORT: self._port, CONF_TOKEN: token},
                )
        return self.async_show_form(
            step_id="token",
            data_schema=TOKEN_SCHEMA,
            errors=errors,
            description_placeholders={"name": self._name, "host": self._host},
        )

    # ---- manual ---------------------------------------------------------------------------------

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            host = user_input[CONF_HOST].strip()
            port = user_input[CONF_PORT]
            token = clean_token(user_input[CONF_TOKEN])
            info, error = await self._probe(host, port, token)
            if error:
                errors["base"] = error
            else:
                assert info is not None
                await self.async_set_unique_id(info.get("id") or f"{host}:{port}")
                self._abort_if_unique_id_configured(updates={CONF_HOST: host, CONF_PORT: port, CONF_TOKEN: token})
                return self.async_create_entry(
                    title=info.get("name") or DEFAULT_NAME,
                    data={CONF_HOST: host, CONF_PORT: port, CONF_TOKEN: token},
                )
        schema = vol.Schema(
            {
                vol.Required(CONF_HOST): str,
                vol.Required(CONF_PORT, default=DEFAULT_PORT): vol.All(vol.Coerce(int), vol.Range(1, 65535)),
                vol.Required(CONF_TOKEN): str,
            }
        )
        return self.async_show_form(
            step_id="user", data_schema=self.add_suggested_values_to_schema(schema, user_input), errors=errors
        )

    # ---- new token ------------------------------------------------------------------------------

    async def async_step_reauth(self, entry_data: Mapping[str, Any]) -> ConfigFlowResult:
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        entry = self._get_reauth_entry()
        errors: dict[str, str] = {}
        if user_input is not None:
            token = clean_token(user_input[CONF_TOKEN])
            _, error = await self._probe(entry.data[CONF_HOST], entry.data[CONF_PORT], token)
            if error:
                errors["base"] = error
            else:
                return self.async_update_reload_and_abort(entry, data_updates={CONF_TOKEN: token})
        return self.async_show_form(
            step_id="reauth_confirm",
            data_schema=TOKEN_SCHEMA,
            errors=errors,
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
