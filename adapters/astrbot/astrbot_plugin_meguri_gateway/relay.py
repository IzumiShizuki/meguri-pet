from __future__ import annotations

from typing import Any, Protocol
from urllib.parse import quote, urlparse

import httpx
from pydantic import BaseModel, Field, ValidationError


class RelayUnavailableError(RuntimeError):
    pass


class RelayProtocolError(RuntimeError):
    pass


IDENTIFIER_PATTERN = r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"


class RemoteDevice(BaseModel):
    device_id: str = Field(min_length=1, pattern=IDENTIFIER_PATTERN)
    label: str = Field(min_length=1)
    status: str = Field(min_length=1)
    capabilities: list[str] = Field(default_factory=list)
    workspace_roots: list[str] = Field(default_factory=list)
    busy: bool = False


class RemoteTaskDraft(BaseModel):
    draft_id: str = Field(min_length=1, pattern=IDENTIFIER_PATTERN)
    target_device: RemoteDevice
    summary: str = Field(min_length=1)
    confirmation_code: str = Field(min_length=1)
    expires_at: str = Field(min_length=1)
    permissions: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class RemoteTask(BaseModel):
    task_id: str = Field(min_length=1, pattern=IDENTIFIER_PATTERN)
    status: str = Field(min_length=1)
    target_device_id: str = Field(min_length=1, pattern=IDENTIFIER_PATTERN)
    summary: str = ""
    detail: str | None = None
    updated_at: str | None = None
    waiting_for_approval: bool = False


class MeguriRelayClient(Protocol):
    async def list_devices(self, user_id: str, session_id: str) -> list[RemoteDevice]: ...

    async def preview_task(
        self,
        *,
        user_id: str,
        session_id: str,
        prompt: str,
        target_device_id: str | None,
        source_message_id: str,
    ) -> RemoteTaskDraft: ...

    async def confirm_task(
        self,
        *,
        user_id: str,
        session_id: str,
        draft_id: str,
        confirmation_code: str,
        source_message_id: str,
    ) -> RemoteTask: ...

    async def get_task(self, *, user_id: str, session_id: str, task_id: str) -> RemoteTask: ...

    async def cancel_task(
        self,
        *,
        user_id: str,
        session_id: str,
        task_id: str,
        source_message_id: str,
    ) -> RemoteTask: ...


class HttpMeguriRelayClient:
    """HTTP adapter for the future authoritative Meguri Relay service."""

    def __init__(
        self,
        base_url: str,
        *,
        token: str | None = None,
        timeout_seconds: float = 8.0,
        transport: httpx.AsyncBaseTransport | None = None,
        tenant_id: str = "meguri-local",
    ) -> None:
        self.base_url = base_url.rstrip("/")
        parsed = urlparse(self.base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError("relay URL must be an absolute HTTP(S) URL")
        if parsed.hostname in {"0.0.0.0", "::"}:
            raise ValueError("relay URL must not use a wildcard address")
        loopback = parsed.hostname in {"127.0.0.1", "localhost", "::1"}
        if not loopback and parsed.scheme != "https":
            raise ValueError("non-loopback relay URL must use HTTPS")
        if not token:
            raise ValueError("Meguri Relay requires an access token")
        self.tenant_id = tenant_id
        self._timeout = httpx.Timeout(timeout_seconds)
        self._transport = transport
        self._headers = {"Authorization": f"Bearer {token}"}
        self._client: httpx.AsyncClient | None = None

    async def list_devices(self, user_id: str, session_id: str) -> list[RemoteDevice]:
        value = await self._request(
            "GET",
            "/v1/remote/devices",
            headers=self._identity_headers(user_id, session_id),
        )
        items = value.get("devices")
        if not isinstance(items, list):
            raise RelayProtocolError("relay response is missing devices")
        try:
            return [RemoteDevice.model_validate(item) for item in items]
        except ValidationError as exc:
            raise RelayProtocolError("relay returned an invalid device") from exc

    async def preview_task(
        self,
        *,
        user_id: str,
        session_id: str,
        prompt: str,
        target_device_id: str | None,
        source_message_id: str,
    ) -> RemoteTaskDraft:
        headers = self._identity_headers(user_id, session_id)
        headers["Idempotency-Key"] = source_message_id
        value = await self._request(
            "POST",
            "/v1/remote/tasks/preview",
            headers=headers,
            json={
                "prompt": prompt,
                "target_device_id": target_device_id,
                "source": "astrbot",
                "source_message_id": source_message_id,
            },
        )
        return self._validate(RemoteTaskDraft, value, "task draft")

    async def confirm_task(
        self,
        *,
        user_id: str,
        session_id: str,
        draft_id: str,
        confirmation_code: str,
        source_message_id: str,
    ) -> RemoteTask:
        headers = self._identity_headers(user_id, session_id)
        headers["Idempotency-Key"] = source_message_id
        value = await self._request(
            "POST",
            f"/v1/remote/tasks/{quote(draft_id, safe='')}/confirm",
            headers=headers,
            json={"confirmation_code": confirmation_code},
        )
        return self._validate(RemoteTask, value, "task")

    async def get_task(self, *, user_id: str, session_id: str, task_id: str) -> RemoteTask:
        value = await self._request(
            "GET",
            f"/v1/remote/tasks/{quote(task_id, safe='')}",
            headers=self._identity_headers(user_id, session_id),
        )
        return self._validate(RemoteTask, value, "task")

    async def cancel_task(
        self,
        *,
        user_id: str,
        session_id: str,
        task_id: str,
        source_message_id: str,
    ) -> RemoteTask:
        headers = self._identity_headers(user_id, session_id)
        headers["Idempotency-Key"] = source_message_id
        value = await self._request(
            "POST",
            f"/v1/remote/tasks/{quote(task_id, safe='')}/cancel",
            headers=headers,
        )
        return self._validate(RemoteTask, value, "task")

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    def _get_client(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient(
                base_url=self.base_url,
                timeout=self._timeout,
                transport=self._transport,
                headers=self._headers,
            )
        return self._client

    def _identity_headers(self, user_id: str, session_id: str) -> dict[str, str]:
        return {
            "X-Meguri-Tenant-ID": self.tenant_id,
            "X-Meguri-User-ID": user_id,
            "X-Meguri-Client-ID": "astrbot",
            "X-Meguri-Actor-ID": user_id,
            "X-Meguri-Session-ID": session_id,
        }

    @staticmethod
    def _validate(model: type[BaseModel], value: dict[str, Any], label: str):
        try:
            return model.model_validate(value)
        except ValidationError as exc:
            raise RelayProtocolError(f"relay returned an invalid {label}") from exc

    async def _request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        try:
            response = await self._get_client().request(method, path, **kwargs)
            response.raise_for_status()
        except httpx.RequestError as exc:
            raise RelayUnavailableError("Meguri Relay is unavailable") from exc
        except httpx.HTTPStatusError as exc:
            raise RelayProtocolError(f"relay returned HTTP {exc.response.status_code}") from exc
        try:
            value = response.json()
        except ValueError as exc:
            raise RelayProtocolError("relay returned invalid JSON") from exc
        if not isinstance(value, dict):
            raise RelayProtocolError("relay response must be an object")
        return value
