from __future__ import annotations

from typing import Any, Protocol
from urllib.parse import urlparse

import httpx


class CoreUnavailableError(RuntimeError):
    pass


class CoreProtocolError(RuntimeError):
    pass


class MeguriCoreClient(Protocol):
    async def respond(self, payload: dict[str, Any]) -> dict[str, Any]: ...
    async def runtime_state(self, user_id: str, session_id: str) -> dict[str, Any]: ...
    async def set_override(self, user_id: str, override: dict[str, Any]) -> dict[str, Any]: ...
    async def clear_override(self, scope: str) -> dict[str, Any]: ...


class HttpMeguriCoreClient:
    def __init__(
        self,
        base_url: str = "http://127.0.0.1:8100",
        timeout_seconds: float = 8.0,
        transport: httpx.AsyncBaseTransport | None = None,
        allow_non_loopback: bool = False,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        host = urlparse(self.base_url).hostname
        if host in {"0.0.0.0", "::"}:
            raise ValueError("meguri-core must not use a wildcard address")
        if not allow_non_loopback and host not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("AstrBot gateway requires a loopback meguri-core URL")
        self.timeout = httpx.Timeout(timeout_seconds)
        self.transport = transport

    async def respond(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._request("POST", "/v1/chat/respond", json=payload)

    async def runtime_state(self, user_id: str, session_id: str) -> dict[str, Any]:
        return await self._request(
            "GET",
            "/v1/runtime/state",
            params={"user_id": user_id, "client_id": "astrbot", "session_id": session_id},
        )

    async def set_override(self, user_id: str, override: dict[str, Any]) -> dict[str, Any]:
        return await self._request("POST", "/v1/runtime/override", params={"user_id": user_id}, json=override)

    async def clear_override(self, scope: str) -> dict[str, Any]:
        return await self._request("DELETE", f"/v1/runtime/override/{scope}")

    async def _request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        try:
            async with httpx.AsyncClient(
                base_url=self.base_url,
                timeout=self.timeout,
                transport=self.transport,
            ) as client:
                response = await client.request(method, path, **kwargs)
                response.raise_for_status()
        except (httpx.TimeoutException, httpx.NetworkError) as exc:
            raise CoreUnavailableError("meguri-core is unavailable") from exc
        except httpx.HTTPStatusError as exc:
            raise CoreProtocolError(f"meguri-core returned HTTP {exc.response.status_code}") from exc
        try:
            value = response.json()
        except ValueError as exc:
            raise CoreProtocolError("meguri-core returned invalid JSON") from exc
        if not isinstance(value, dict):
            raise CoreProtocolError("meguri-core response must be an object")
        return value
