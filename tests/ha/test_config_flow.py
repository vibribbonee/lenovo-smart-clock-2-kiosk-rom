"""Config flow: zeroconf, manual entry, token errors, reauth."""

from __future__ import annotations

from ipaddress import ip_address

from unittest.mock import patch

from homeassistant import config_entries
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

from pytest_homeassistant_custom_component.test_util.aiohttp import AiohttpClientMocker

from .conftest import BASE, DEVICE_ID, DOMAIN, HOST, INFO, STATUS, TOKEN

DISCOVERY = ZeroconfServiceInfo(
    ip_address=ip_address(HOST),
    ip_addresses=[ip_address(HOST)],
    hostname="smart-clock.local.",
    name="Smart Clock 2 AB12._kioskclock._tcp.local.",
    port=8765,
    type="_kioskclock._tcp.local.",
    properties={"id": DEVICE_ID, "model": "Lenovo CD-24502F", "version": "0.9.0", "api": "2"},
)


async def test_zeroconf_then_token(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    aioclient_mock.get(f"{BASE}/api/info", json=INFO)
    result = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=DISCOVERY
    )
    assert result["type"] is FlowResultType.FORM
    assert result["step_id"] == "token"
    assert result["description_placeholders"] == {"name": "Smart Clock 2 AB12", "host": HOST}

    # The clock shows the token in groups of four.
    spaced = " ".join(TOKEN[i : i + 4] for i in range(0, len(TOKEN), 4))
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"token": spaced})
    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert result["title"] == "Smart Clock 2 AB12"
    assert result["data"] == {CONF_HOST: HOST, CONF_PORT: 8765, "token": TOKEN}
    assert result["result"].unique_id == DEVICE_ID
    assert aioclient_mock.mock_calls[-1][3]["Authorization"] == f"Bearer {TOKEN}"


async def test_zeroconf_wrong_token_then_right(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    aioclient_mock.get(f"{BASE}/api/info", status=401, json={"error": "unauthorized"})
    result = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=DISCOVERY
    )
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"token": "nope"})
    assert result["type"] is FlowResultType.FORM
    assert result["errors"] == {"base": "invalid_auth"}

    aioclient_mock.clear_requests()
    aioclient_mock.get(f"{BASE}/api/info", json=INFO)
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"token": TOKEN})
    assert result["type"] is FlowResultType.CREATE_ENTRY


async def test_zeroconf_updates_host_of_known_clock(hass: HomeAssistant, config_entry) -> None:
    config_entry.add_to_hass(hass)
    moved = ZeroconfServiceInfo(
        ip_address=ip_address("192.0.2.40"), ip_addresses=[ip_address("192.0.2.40")],
        hostname=DISCOVERY.hostname, name=DISCOVERY.name, port=8765, type=DISCOVERY.type,
        properties=DISCOVERY.properties,
    )
    result = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=moved
    )
    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "already_configured"
    assert config_entry.data[CONF_HOST] == "192.0.2.40"


async def test_zeroconf_ipv6_ignored(hass: HomeAssistant) -> None:
    v6 = ZeroconfServiceInfo(
        ip_address=ip_address("fe80::1"), ip_addresses=[ip_address("fe80::1")], hostname=DISCOVERY.hostname,
        name=DISCOVERY.name, port=8765, type=DISCOVERY.type, properties=DISCOVERY.properties,
    )
    result = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=v6
    )
    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "not_ipv4"


async def test_user_flow(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    assert result["type"] is FlowResultType.FORM
    assert result["step_id"] == "user"

    aioclient_mock.get(f"{BASE}/api/info", exc=TimeoutError)
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {CONF_HOST: HOST, CONF_PORT: 8765, "token": TOKEN}
    )
    assert result["type"] is FlowResultType.FORM
    assert result["errors"] == {"base": "cannot_connect"}

    aioclient_mock.clear_requests()
    aioclient_mock.get(f"{BASE}/api/info", json=INFO)
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {CONF_HOST: f" {HOST} ", CONF_PORT: 8765, "token": TOKEN}
    )
    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert result["result"].unique_id == DEVICE_ID
    assert result["data"][CONF_HOST] == HOST


async def test_user_flow_old_clockbridge(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    """ClockBridge before 0.9.0 has no /api/info: fall back to /api/status and host:port as id."""
    aioclient_mock.get(f"{BASE}/api/info", status=404, json={"error": "not found"})
    aioclient_mock.get(f"{BASE}/api/status", json=STATUS)
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {CONF_HOST: HOST, CONF_PORT: 8765, "token": TOKEN}
    )
    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert result["title"] == "Smart Clock 2"
    assert result["result"].unique_id == f"{HOST}:8765"


async def test_user_flow_already_configured(hass: HomeAssistant, aioclient_mock, config_entry) -> None:
    config_entry.add_to_hass(hass)
    aioclient_mock.get(f"{BASE}/api/info", json=INFO)
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {CONF_HOST: HOST, CONF_PORT: 8765, "token": TOKEN}
    )
    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "already_configured"


async def test_reauth(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker, config_entry) -> None:
    config_entry.add_to_hass(hass)
    result = await config_entry.start_reauth_flow(hass)
    assert result["type"] is FlowResultType.MENU and result["step_id"] == "reauth"
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"next_step_id": "reauth_confirm"})
    assert result["step_id"] == "reauth_confirm"

    new = "f" * 32
    aioclient_mock.get(f"{BASE}/api/info", json=INFO)
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"token": new})
    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "reauth_successful"
    assert config_entry.data["token"] == new


PAIRABLE = ZeroconfServiceInfo(
    ip_address=ip_address(HOST), ip_addresses=[ip_address(HOST)], hostname=DISCOVERY.hostname,
    name=DISCOVERY.name, port=8765, type=DISCOVERY.type,
    properties={**DISCOVERY.properties, "version": "0.9.3", "api": "4"},
)


async def _pair_flow(hass, aioclient_mock, poll_state: str):
    aioclient_mock.post(f"{BASE}/api/pair", json={"id": "abc", "code": "4821", "expires_in": 120})
    body = {"state": poll_state, **({"token": TOKEN} if poll_state == "allowed" else {})}
    aioclient_mock.get(f"{BASE}/api/pair", json=body)
    aioclient_mock.get(f"{BASE}/api/info", json=INFO)
    with patch("custom_components.kioskclock.config_flow.PAIR_POLL_S", 0):
        result = await hass.config_entries.flow.async_init(
            DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=PAIRABLE
        )
        assert result["type"] is FlowResultType.MENU and result["step_id"] == "method"
        result = await hass.config_entries.flow.async_configure(result["flow_id"], {"next_step_id": "pair"})
        assert result["type"] is FlowResultType.SHOW_PROGRESS
        assert result["description_placeholders"]["code"] == "4 8 2 1"
        await hass.async_block_till_done()
        return await hass.config_entries.flow.async_configure(result["flow_id"])


async def test_pair_on_clock(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    result = await _pair_flow(hass, aioclient_mock, "allowed")
    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert result["data"] == {CONF_HOST: HOST, CONF_PORT: 8765, "token": TOKEN}
    assert result["result"].unique_id == DEVICE_ID
    # Pairing itself sends no token.
    pair_calls = [c for c in aioclient_mock.mock_calls if "/api/pair" in str(c[1])]
    assert pair_calls and all(c[3]["Authorization"] == "Bearer " for c in pair_calls)


async def test_pair_denied_offers_token(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    result = await _pair_flow(hass, aioclient_mock, "denied")
    assert result["type"] is FlowResultType.MENU and result["step_id"] == "pair_failed"
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"next_step_id": "token"})
    assert result["type"] is FlowResultType.FORM and result["step_id"] == "token"


async def test_user_without_token_offers_pairing(hass: HomeAssistant) -> None:
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {CONF_HOST: HOST, CONF_PORT: 8765})
    assert result["type"] is FlowResultType.MENU and result["step_id"] == "method"
