"""Small async client for the ClockBridge HTTP API (port 8765, bearer token)."""

from __future__ import annotations

import asyncio
from typing import Any

import aiohttp


class KioskClockError(Exception):
    """Any API failure."""


class CannotConnect(KioskClockError):
    """The clock did not answer."""


class InvalidAuth(KioskClockError):
    """The token was rejected (HTTP 401)."""


class NotSupported(KioskClockError):
    """The endpoint does not exist on this ClockBridge version (HTTP 404)."""


class Busy(KioskClockError):
    """Another pairing request is still waiting on the clock (HTTP 409)."""


def clean_token(token: str) -> str:
    """The clock shows the token in groups of four; drop all whitespace."""
    return "".join(token.split())


class KioskClockApi:
    """ClockBridge API. The server handles one request per connection, one at a time."""

    def __init__(self, session: aiohttp.ClientSession, host: str, port: int, token: str) -> None:
        self._session = session
        self.host = host
        self.port = port
        self._token = clean_token(token)
        self._lock = asyncio.Lock()

    async def _request(self, method: str, path: str, params: dict[str, str] | None = None) -> dict[str, Any]:
        url = f"http://{self.host}:{self.port}{path}"
        headers = {"Authorization": f"Bearer {self._token}"}
        async with self._lock:
            try:
                async with asyncio.timeout(10):
                    async with self._session.request(method, url, params=params, headers=headers) as resp:
                        if resp.status == 401:
                            raise InvalidAuth
                        if resp.status == 404:
                            raise NotSupported(path)
                        if resp.status == 409:
                            raise Busy(path)
                        try:
                            body = await resp.json(content_type=None)
                        except ValueError as err:
                            raise KioskClockError(f"{path}: invalid JSON") from err
                        if resp.status >= 400:
                            error = body.get("error") if isinstance(body, dict) else None
                            raise KioskClockError(f"{path}: HTTP {resp.status} {error or ''}".strip())
                        return body if isinstance(body, dict) else {}
            except (aiohttp.ClientError, TimeoutError) as err:
                raise CannotConnect(f"{self.host}:{self.port}: {err!r}") from err

    async def pair_start(self, name: str) -> dict[str, Any]:
        """Ask the clock to pair (no token needed): {"id", "code"}; the person at the clock taps Allow."""
        return await self._request("POST", "/api/pair", {"name": name})

    async def pair_poll(self, pair_id: str) -> dict[str, Any]:
        """{"state": pending|allowed|denied|expired|..., "token" once allowed}."""
        return await self._request("GET", "/api/pair", {"id": pair_id})

    async def info(self) -> dict[str, Any]:
        """Device identity (ClockBridge 0.9.0+, NotSupported before)."""
        return await self._request("GET", "/api/info")

    async def dock(self) -> dict[str, Any]:
        return await self._request("GET", "/api/dock")

    async def status(self) -> dict[str, Any]:
        return await self._request("GET", "/api/status")

    async def settings(self) -> dict[str, Any]:
        return await self._request("GET", "/api/settings")

    async def set_setting(self, key: str, value: str) -> dict[str, Any]:
        return await self._request("POST", "/api/settings", {"key": key, "value": value})

    async def set_light(self, level: int) -> dict[str, Any]:
        return await self._request("POST", "/api/dock/light", {"level": str(level)})

    async def screen(self, on: bool) -> dict[str, Any]:
        return await self._request("POST", f"/api/screen/{'on' if on else 'off'}")

    async def check_update(self) -> dict[str, Any]:
        return await self._request("POST", "/api/update/check")

    async def run_gesture(self, gesture: str) -> dict[str, Any]:
        """Run the action set for a gesture (hold_up, hold_down, tap_single, tap_double), as if done on the clock."""
        return await self._request("POST", "/api/gesture", {"name": gesture})

    async def run_action(self, action: str) -> dict[str, Any]:
        """Run one gesture action directly (voice, play_pause, dock_light, ...)."""
        return await self._request("POST", "/api/action", {"name": action})

    async def set_webhook(self, url: str) -> dict[str, Any]:
        """Point the clock's event webhook at url ("" clears it). ClockBridge 0.9.0+."""
        return await self._request("POST", "/api/webhook", {"url": url})
