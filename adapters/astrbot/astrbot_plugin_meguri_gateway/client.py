from __future__ import annotations

import json
from typing import Any, Protocol
from urllib.parse import quote, urlparse

import httpx

from .turn_checkpoints import TurnCheckpointStore


class CoreUnavailableError(RuntimeError):
    pass


class CoreProtocolError(RuntimeError):
    pass


_KNOWN_EVENT_TYPES = frozenset(
    {
        "turn.started",
        "turn.stage.changed",
        "retrieval.completed",
        "text.delta",
        "text.completed",
        "semantic.completed",
        "expression.cue",
        "sprite.resolved",
        "memory.candidate.created",
        "memory.write.completed",
        "tool.started",
        "tool.completed",
        "training.candidates.ready",
        "tts.requested",
        "tts.audio.delta",
        "tts.completed",
        "session.synced",
        "turn.completed",
        "turn.cancelled",
        "turn.failed",
    }
)
_TERMINAL_EVENT_TYPES = frozenset(
    {"turn.completed", "turn.cancelled", "turn.failed"}
)


class MeguriCoreClient(Protocol):
    async def respond(
        self,
        payload: dict[str, Any],
        *,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]: ...
    async def runtime_state(self, user_id: str, session_id: str) -> dict[str, Any]: ...
    async def set_override(self, user_id: str, override: dict[str, Any]) -> dict[str, Any]: ...
    async def clear_override(self, scope: str) -> dict[str, Any]: ...
    async def latest_daily_report(self, kind: str) -> dict[str, Any] | None: ...
    async def close(self) -> None: ...


class HttpMeguriCoreClient:
    def __init__(
        self,
        base_url: str = "http://127.0.0.1:18080",
        timeout_seconds: float = 8.0,
        transport: httpx.AsyncBaseTransport | None = None,
        allow_non_loopback: bool = False,
        tenant_id: str = "meguri-local",
        shared_token: str | None = None,
        async_turns_enabled: bool = True,
        checkpoint_store: TurnCheckpointStore | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        parsed = urlparse(self.base_url)
        host = parsed.hostname
        if parsed.scheme not in {"http", "https"} or not host:
            raise ValueError("meguri-core URL must be an absolute HTTP(S) URL")
        if host in {"0.0.0.0", "::"}:
            raise ValueError("meguri-core must not use a wildcard address")
        if not allow_non_loopback and host not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("AstrBot gateway requires a loopback meguri-core URL")
        self.tenant_id = tenant_id
        self.async_turns_enabled = async_turns_enabled
        self._checkpoint_store = checkpoint_store
        self._timeout = httpx.Timeout(timeout_seconds)
        self._transport = transport
        self._headers = (
            {"Authorization": f"Bearer {shared_token}"} if shared_token else {}
        )
        self._client: httpx.AsyncClient | None = None

    async def respond(
        self,
        payload: dict[str, Any],
        *,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        if self.async_turns_enabled:
            streamed = await self._respond_with_turn(
                payload,
                idempotency_key=idempotency_key,
            )
            if streamed is not None:
                return streamed
        return await self._request(
            "POST",
            "/v1/chat/respond",
            json=payload,
            headers=self._identity_headers(
                str(payload.get("user_id", "")),
                str(payload.get("session_id", "")),
                formal_memory_allowed=bool(payload.get("formal_memory_allowed", False)),
            ),
        )

    async def _respond_with_turn(
        self,
        payload: dict[str, Any],
        *,
        idempotency_key: str | None,
    ) -> dict[str, Any] | None:
        user_id = str(payload.get("user_id", ""))
        session_id = str(payload.get("session_id", ""))
        headers = self._identity_headers(
            user_id,
            session_id,
            formal_memory_allowed=bool(payload.get("formal_memory_allowed", False)),
        )
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        try:
            response = await self._get_client().request(
                "POST",
                "/v1/turns",
                json=payload,
                headers=headers,
            )
        except httpx.RequestError as exc:
            raise CoreUnavailableError("meguri-core is unavailable") from exc
        if response.status_code in {404, 405, 501}:
            return None
        self._raise_for_status(response)
        created = self._json_object(response)
        turn_id = _required_string(created, "turn_id")
        created_session = _required_string(created, "session_id")
        if created_session != session_id:
            raise CoreProtocolError("meguri-core returned a mismatched session_id")
        return await self._consume_turn_events(
            turn_id=turn_id,
            session_id=session_id,
            build_id=str(created.get("build_id", "")),
            headers=headers,
        )

    async def _consume_turn_events(
        self,
        *,
        turn_id: str,
        session_id: str,
        build_id: str,
        headers: dict[str, str],
    ) -> dict[str, Any]:
        state: dict[str, Any] = {
            "checkpoint": 0,
            "seen_event_ids": set(),
            "semantic": None,
            "runtime_state": None,
            "expression": None,
            "memory_status": "unavailable",
            "text": None,
            "build_id": build_id,
            "terminal": None,
        }
        saved = (
            self._checkpoint_store.load(session_id, turn_id)
            if self._checkpoint_store is not None
            else None
        )
        if saved is not None:
            state.update(saved)
            if build_id:
                state["build_id"] = build_id
        saved_terminal = state.get("terminal")
        if isinstance(saved_terminal, str):
            if saved_terminal != "turn.completed":
                raise CoreProtocolError(f"meguri-core turn ended with {saved_terminal}")
            return _assembled_turn_response(turn_id, session_id, state)
        for _attempt in range(3):
            terminal = await self._consume_event_stream_once(
                turn_id=turn_id,
                session_id=session_id,
                headers=headers,
                state=state,
            )
            if terminal is None:
                continue
            if terminal != "turn.completed":
                raise CoreProtocolError(f"meguri-core turn ended with {terminal}")
            return _assembled_turn_response(turn_id, session_id, state)
        raise CoreProtocolError("meguri-core event stream ended before a terminal event")

    async def _consume_event_stream_once(
        self,
        *,
        turn_id: str,
        session_id: str,
        headers: dict[str, str],
        state: dict[str, Any],
    ) -> str | None:
        path = f"/v1/sessions/{quote(session_id, safe='')}/events"
        try:
            async with self._get_client().stream(
                "GET",
                path,
                params={"after_sequence": state["checkpoint"]},
                headers={**headers, "Accept": "text/event-stream"},
            ) as response:
                self._raise_for_status(response)
                data_lines: list[str] = []
                async for line in response.aiter_lines():
                    if line == "":
                        terminal = _accept_sse_data(
                            data_lines,
                            turn_id=turn_id,
                            session_id=session_id,
                            state=state,
                        )
                        data_lines = []
                        self._persist_checkpoint(
                            session_id, turn_id, state, terminal=terminal
                        )
                        if terminal is not None:
                            return terminal
                    elif line.startswith("data:"):
                        data_lines.append(line[5:].lstrip())
                terminal = _accept_sse_data(
                    data_lines,
                    turn_id=turn_id,
                    session_id=session_id,
                    state=state,
                )
                self._persist_checkpoint(
                    session_id, turn_id, state, terminal=terminal
                )
                return terminal
        except httpx.TimeoutException:
            return None
        except httpx.RequestError as exc:
            raise CoreUnavailableError("meguri-core event stream is unavailable") from exc

    def _persist_checkpoint(
        self,
        session_id: str,
        turn_id: str,
        state: dict[str, Any],
        *,
        terminal: str | None,
    ) -> None:
        if terminal is not None:
            state["terminal"] = terminal
        if self._checkpoint_store is not None:
            self._checkpoint_store.save(session_id, turn_id, state)

    async def runtime_state(self, user_id: str, session_id: str) -> dict[str, Any]:
        return await self._request(
            "GET",
            "/v1/runtime/state",
            params={"user_id": user_id, "client_id": "astrbot", "session_id": session_id},
            headers=self._identity_headers(user_id, session_id),
        )

    async def set_override(self, user_id: str, override: dict[str, Any]) -> dict[str, Any]:
        return await self._request(
            "POST",
            "/v1/runtime/override",
            params={"user_id": user_id},
            json=override,
            headers=self._identity_headers(user_id, "runtime-override"),
        )

    async def clear_override(self, scope: str) -> dict[str, Any]:
        return await self._request(
            "DELETE",
            f"/v1/runtime/override/{quote(scope, safe='')}",
            headers=self._identity_headers(scope, "runtime-override"),
        )

    async def latest_daily_report(self, kind: str) -> dict[str, Any] | None:
        try:
            response = await self._get_client().request(
                "GET",
                "/v1/daily/reports/latest",
                params={"kind": kind},
                headers=self._identity_headers("daily-report-poller", "daily-report-poller"),
            )
            if response.status_code == 404:
                return None
            response.raise_for_status()
        except httpx.RequestError as exc:
            raise CoreUnavailableError("meguri-core is unavailable") from exc
        except httpx.HTTPStatusError as exc:
            raise CoreProtocolError(
                f"meguri-core returned HTTP {exc.response.status_code}"
            ) from exc
        try:
            value = response.json()
        except ValueError as exc:
            raise CoreProtocolError("meguri-core returned invalid JSON") from exc
        if not isinstance(value, dict):
            raise CoreProtocolError("meguri-core response must be an object")
        return value

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
                # The AstrBot container has global HTTP proxy variables. Core is
                # deliberately loopback-only, so inheriting those variables
                # would send 127.0.0.1 traffic to the proxy and return HTTP 502.
                trust_env=False,
            )
        return self._client

    def _identity_headers(
        self,
        user_id: str,
        session_id: str,
        *,
        formal_memory_allowed: bool = False,
    ) -> dict[str, str]:
        return {
            "X-Meguri-Tenant-ID": self.tenant_id,
            "X-Meguri-User-ID": user_id,
            "X-Meguri-Client-ID": "astrbot",
            "X-Meguri-Actor-ID": user_id,
            "X-Meguri-Actor-Type": "user",
            "X-Meguri-Session-ID": session_id,
            "X-Meguri-Formal-Memory-Allowed": str(formal_memory_allowed).lower(),
        }

    async def _request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        try:
            response = await self._get_client().request(method, path, **kwargs)
            self._raise_for_status(response)
        except httpx.RequestError as exc:
            raise CoreUnavailableError("meguri-core is unavailable") from exc
        return self._json_object(response)

    @staticmethod
    def _raise_for_status(response: httpx.Response) -> None:
        try:
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            raise CoreProtocolError(
                f"meguri-core returned HTTP {exc.response.status_code}"
            ) from exc

    @staticmethod
    def _json_object(response: httpx.Response) -> dict[str, Any]:
        try:
            value = response.json()
        except ValueError as exc:
            raise CoreProtocolError("meguri-core returned invalid JSON") from exc
        if not isinstance(value, dict):
            raise CoreProtocolError("meguri-core response must be an object")
        return value


def _accept_sse_data(
    data_lines: list[str],
    *,
    turn_id: str,
    session_id: str,
    state: dict[str, Any],
) -> str | None:
    if not data_lines:
        return None
    try:
        value = json.loads("\n".join(data_lines))
    except json.JSONDecodeError as exc:
        raise CoreProtocolError("meguri-core returned invalid SSE JSON") from exc
    if not isinstance(value, dict):
        raise CoreProtocolError("meguri-core event envelope must be an object")
    if value.get("protocol_version") != "1.0":
        raise CoreProtocolError("meguri-core returned an unsupported protocol version")
    event_id = _required_string(value, "event_id")
    event_type = _required_string(value, "type")
    event_turn_id = _required_string(value, "turn_id")
    event_session_id = _required_string(value, "session_id")
    required = value.get("required")
    sequence = value.get("sequence")
    data = value.get("data")
    metadata = value.get("metadata")
    if not isinstance(required, bool):
        raise CoreProtocolError("meguri-core event required flag is invalid")
    if not isinstance(sequence, int) or isinstance(sequence, bool) or sequence < 1:
        raise CoreProtocolError("meguri-core event sequence is invalid")
    if not isinstance(data, dict) or not isinstance(metadata, dict):
        raise CoreProtocolError("meguri-core event payload is invalid")
    if event_session_id != session_id:
        raise CoreProtocolError("meguri-core event crossed the requested session scope")
    checkpoint = int(state["checkpoint"])
    seen_event_ids: set[str] = state["seen_event_ids"]
    if sequence <= checkpoint:
        if event_id in seen_event_ids:
            return None
        raise CoreProtocolError("meguri-core replay changed an acknowledged sequence")
    if sequence != checkpoint + 1:
        raise CoreProtocolError("meguri-core event sequence contains a gap")
    state["checkpoint"] = sequence
    if event_id in seen_event_ids:
        return None
    seen_event_ids.add(event_id)
    if event_type not in _KNOWN_EVENT_TYPES:
        if required:
            raise CoreProtocolError(f"unsupported required event type: {event_type}")
        return None
    if event_turn_id != turn_id:
        return None
    build = metadata.get("build_id")
    if isinstance(build, str) and build:
        state["build_id"] = build
    if event_type == "turn.started" and isinstance(data.get("runtime_state"), dict):
        state["runtime_state"] = data["runtime_state"]
    elif event_type == "semantic.completed":
        state["semantic"] = data
    elif event_type in {"expression.cue", "sprite.resolved"}:
        state["expression"] = data
    elif event_type == "text.completed" and isinstance(data.get("text"), str):
        state["text"] = data["text"]
    elif event_type == "memory.write.completed":
        status = data.get("status")
        if status in {"written", "pending", "unavailable"}:
            state["memory_status"] = status
    return event_type if event_type in _TERMINAL_EVENT_TYPES else None


def _assembled_turn_response(
    turn_id: str,
    session_id: str,
    state: dict[str, Any],
) -> dict[str, Any]:
    semantic = state.get("semantic")
    runtime_state = state.get("runtime_state")
    expression = state.get("expression")
    if not isinstance(semantic, dict) or not isinstance(runtime_state, dict):
        raise CoreProtocolError("meguri-core event stream is missing semantic runtime data")
    if not isinstance(expression, dict):
        expression = {
            key: semantic[key]
            for key in (
                "expression_tag",
                "expression_intensity",
                "outfit_code",
                "expression_code",
                "sprite_file",
            )
            if key in semantic
        }
    reply = semantic.get("reply") or state.get("text")
    if not isinstance(reply, str) or not reply:
        raise CoreProtocolError("meguri-core event stream is missing the completed text")
    response = {
        "reply": reply,
        "expression_tag": semantic.get("expression_tag", "neutral"),
        "expression_intensity": semantic.get("expression_intensity", "low"),
        "voice_style": semantic.get("voice_style", "neutral"),
        "memory_candidates": semantic.get("memory_candidates", []),
    }
    return {
        "turn_id": turn_id,
        "session_id": session_id,
        "build_id": state.get("build_id", ""),
        "response": response,
        "runtime_state": runtime_state,
        "expression": expression,
        "memory_status": state.get("memory_status", "unavailable"),
    }


def _required_string(value: dict[str, Any], key: str) -> str:
    field = value.get(key)
    if not isinstance(field, str) or not field:
        raise CoreProtocolError(f"meguri-core response is missing {key}")
    return field
