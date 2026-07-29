from __future__ import annotations

import asyncio
import json
from typing import Any, Protocol
from urllib.parse import quote, urlparse

import httpx

from .turn_checkpoints import TurnCheckpointStore


class CoreUnavailableError(RuntimeError):
    pass


class CoreProtocolError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        code: str = "INTERNAL",
        retryable: bool = False,
        details: dict[str, Any] | None = None,
        status_code: int | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.details = details or {}
        self.status_code = status_code


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
        "memory.updated",
        "relationship.updated",
        "tool.proposed",
        "approval.required",
        "approval.resolved",
        "tool.started",
        "tool.completed",
        "tool.failed",
        "skill.started",
        "skill.waiting",
        "skill.completed",
        "skill.failed",
        "agent.started",
        "agent.waiting",
        "agent.completed",
        "agent.failed",
        "semantic.cue",
        "voice.requested",
        "audio.ready",
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
_STATE_EVENT_TYPES = frozenset(
    {
        "turn.started",
        "turn.stage.changed",
        "text.delta",
        "text.completed",
        "semantic.completed",
        "expression.cue",
        "sprite.resolved",
        "memory.updated",
        "relationship.updated",
        "approval.required",
        "session.synced",
        "skill.started",
        "skill.waiting",
        "skill.completed",
        "skill.failed",
        "agent.started",
        "agent.waiting",
        "agent.completed",
        "agent.failed",
        "turn.completed",
        "turn.cancelled",
        "turn.failed",
    }
)
_TERMINAL_EVENT_TYPES = frozenset(
    {"turn.completed", "turn.cancelled", "turn.failed"}
)
_PROTOCOL_VERSIONS = ("1.1", "1.0")
_CAPABILITIES = {
    "text": True,
    "voice": False,
    "sprite": True,
    "screen_context": False,
    "formal_memory": True,
    "sse": True,
}
_PERMISSIONS = {
    "screen_read": False,
    "microphone": False,
    "audio_playback": False,
    "notifications": False,
    "formal_memory_write": False,
}


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
        self.selected_protocol_version: str | None = None
        self.server_capabilities_revision: str | None = None
        self._supported_extensions: frozenset[str] = frozenset()

    async def respond(
        self,
        payload: dict[str, Any],
        *,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        canonical = _canonical_turn_payload(payload)
        if self.async_turns_enabled:
            streamed = await self._respond_with_turn(
                canonical,
                idempotency_key=idempotency_key,
            )
            if streamed is not None:
                return streamed
        legacy = _legacy_turn_payload(canonical)
        return await self._request(
            "POST",
            "/v1/chat/respond",
            json=legacy,
            headers=self._identity_headers(
                str(legacy.get("user_id", "")),
                str(legacy.get("session_id", "")),
                formal_memory_allowed=bool(legacy.get("formal_memory_allowed", False)),
            ),
        )

    def respond_sync(
        self,
        payload: dict[str, Any],
        *,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        """Synchronous wrapper over the exact same Turn/SSE implementation."""
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            async def run_and_close() -> dict[str, Any]:
                try:
                    return await self.respond(
                        payload, idempotency_key=idempotency_key
                    )
                finally:
                    await self.close()

            return asyncio.run(run_and_close())
        raise RuntimeError("respond_sync cannot run inside an active event loop")

    async def _respond_with_turn(
        self,
        payload: dict[str, Any],
        *,
        idempotency_key: str | None,
    ) -> dict[str, Any] | None:
        user_id, session_id, client_instance_id = _identity_values(payload)
        platform_actor_id = _platform_actor_id(payload)
        formal_memory_allowed = bool(payload.get("formal_memory_allowed", False))
        headers = self._identity_headers(
            user_id,
            session_id,
            formal_memory_allowed=formal_memory_allowed,
            actor_id=platform_actor_id,
            client_instance_id=client_instance_id,
        )
        selected = await self._hello(
            payload["identity"],
            headers,
            formal_memory_allowed=formal_memory_allowed,
        )
        payload = {
            key: value
            for key, value in {**payload, "protocol_version": selected}.items()
            if key != "formal_memory_allowed"
        }
        headers["Meguri-Protocol-Version"] = selected
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

    async def _hello(
        self,
        identity: dict[str, Any],
        headers: dict[str, str],
        *,
        formal_memory_allowed: bool,
    ) -> str:
        client_instance = identity.get("client_instance")
        client_instance_id = (
            str(client_instance.get("id", ""))
            if isinstance(client_instance, dict)
            else ""
        )
        response = await self._request(
            "POST",
            "/v1/hello",
            json={
                "protocol_versions": list(_PROTOCOL_VERSIONS),
                "identity": identity,
                "capabilities": {
                    **_CAPABILITIES,
                    "formal_memory": formal_memory_allowed,
                },
                "permissions": {
                    **_PERMISSIONS,
                    "formal_memory_write": formal_memory_allowed,
                },
                "required_extensions": [],
            },
            headers=headers,
        )
        selected = _required_string(response, "selected_protocol_version")
        _assert_protocol_v1(selected)
        revision = _required_string(response, "server_capabilities_revision")
        extensions = response.get("supported_extensions", [])
        if not isinstance(extensions, list) or not all(
            isinstance(item, str) and item for item in extensions
        ):
            raise CoreProtocolError("meguri-core hello extensions are invalid")
        self.selected_protocol_version = selected
        self.server_capabilities_revision = revision
        self._supported_extensions = frozenset(extensions)
        if self._checkpoint_store is not None and client_instance_id:
            self._checkpoint_store.save_negotiation(
                client_instance_id,
                {
                    "selected_protocol_version": selected,
                    "server_capabilities_revision": revision,
                    "supported_extensions": sorted(self._supported_extensions),
                },
            )
        return selected

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
            "once_event_ids": set(),
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
                data_lines: list[str] = []
                event_name: str | None = None
                if response.status_code == 410:
                    terminal = await self._restore_session_snapshot(
                        turn_id=turn_id,
                        session_id=session_id,
                        headers=headers,
                        state=state,
                    )
                    self._persist_checkpoint(
                        session_id, turn_id, state, terminal=terminal
                    )
                    return terminal
                self._raise_for_status(response)
                async for line in response.aiter_lines():
                    if line == "":
                        terminal = None
                        if event_name != "heartbeat":
                            terminal = _accept_sse_data(
                                data_lines,
                                turn_id=turn_id,
                                session_id=session_id,
                                state=state,
                                supported_extensions=self._supported_extensions,
                            )
                        data_lines = []
                        event_name = None
                        self._persist_checkpoint(
                            session_id, turn_id, state, terminal=terminal
                        )
                        if terminal is not None:
                            return terminal
                    elif line.startswith("data:"):
                        data_lines.append(line[5:].lstrip())
                    elif line.startswith("event:"):
                        event_name = line[6:].strip()
                terminal = None
                if event_name != "heartbeat":
                    terminal = _accept_sse_data(
                        data_lines,
                        turn_id=turn_id,
                        session_id=session_id,
                        state=state,
                        supported_extensions=self._supported_extensions,
                    )
                self._persist_checkpoint(
                    session_id, turn_id, state, terminal=terminal
                )
                return terminal
        except httpx.TimeoutException:
            return None
        except httpx.RequestError as exc:
            raise CoreUnavailableError("meguri-core event stream is unavailable") from exc

    async def _restore_session_snapshot(
        self,
        *,
        turn_id: str,
        session_id: str,
        headers: dict[str, str],
        state: dict[str, Any],
    ) -> str | None:
        snapshot = await self._request(
            "GET",
            f"/v1/sessions/{quote(session_id, safe='')}/snapshot",
            headers=headers,
        )
        _assert_protocol_v1(_required_string(snapshot, "protocol_version"))
        if _required_string(snapshot, "session_id") != session_id:
            raise CoreProtocolError("meguri-core snapshot crossed session scope")
        sequence = snapshot.get("sequence")
        turns = snapshot.get("turns")
        if (
            not isinstance(sequence, int)
            or isinstance(sequence, bool)
            or sequence < 0
            or not isinstance(turns, list)
        ):
            raise CoreProtocolError("meguri-core snapshot is invalid")
        state["checkpoint"] = sequence
        event_ids = snapshot.get("processed_event_ids", [])
        once_ids = snapshot.get("processed_once_event_ids", [])
        if not _string_list(event_ids) or not _string_list(once_ids):
            raise CoreProtocolError("meguri-core snapshot event IDs are invalid")
        state["seen_event_ids"] = set(event_ids) | set(once_ids)
        state["once_event_ids"] = set(once_ids)
        target = next(
            (
                item
                for item in turns
                if isinstance(item, dict) and item.get("turn_id") == turn_id
            ),
            None,
        )
        if target is None:
            return None
        text = target.get("text")
        if isinstance(text, str):
            state["text"] = text
            state["semantic"] = {
                "reply": text,
                "expression_tag": "neutral",
                "expression_intensity": "low",
                "voice_style": "neutral",
                "memory_candidates": [],
            }
        if isinstance(target.get("expression"), dict):
            state["expression"] = target["expression"]
        if isinstance(snapshot.get("runtime_state"), dict):
            state["runtime_state"] = snapshot["runtime_state"]
        elif not isinstance(state.get("runtime_state"), dict):
            state["runtime_state"] = {}
        status = target.get("status")
        if status == "completed":
            return "turn.completed"
        if status == "cancelled":
            return "turn.cancelled"
        if status == "failed":
            return "turn.failed"
        return None

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
        actor_id: str | None = None,
        client_instance_id: str | None = None,
    ) -> dict[str, str]:
        headers = {
            "X-Meguri-Tenant-ID": self.tenant_id,
            "X-Meguri-User-ID": user_id,
            "X-Meguri-Client-ID": "astrbot",
            "X-Meguri-Actor-ID": actor_id or user_id,
            "X-Meguri-Actor-Type": "platform_actor",
            "X-Meguri-Session-ID": session_id,
            "X-Meguri-Formal-Memory-Allowed": str(formal_memory_allowed).lower(),
        }
        if client_instance_id:
            headers["X-Meguri-Client-Instance-ID"] = client_instance_id
        return headers

    async def _request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        try:
            response = await self._get_client().request(method, path, **kwargs)
            self._raise_for_status(response)
        except httpx.RequestError as exc:
            raise CoreUnavailableError("meguri-core is unavailable") from exc
        return self._json_object(response)

    @staticmethod
    def _raise_for_status(response: httpx.Response) -> None:
        if response.is_success:
            return
        try:
            body = response.json()
        except ValueError:
            body = None
        error = body.get("error") if isinstance(body, dict) else None
        if isinstance(error, dict):
            code = error.get("code")
            message = error.get("message")
            retryable = error.get("retryable")
            details = error.get("details", {})
            if (
                isinstance(code, str)
                and code
                and isinstance(message, str)
                and message
                and isinstance(retryable, bool)
                and isinstance(details, dict)
            ):
                raise CoreProtocolError(
                    message,
                    code=code,
                    retryable=retryable,
                    details=details,
                    status_code=response.status_code,
                )
        raise CoreProtocolError(
            f"meguri-core returned HTTP {response.status_code}",
            status_code=response.status_code,
        )

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
    supported_extensions: frozenset[str],
) -> str | None:
    if not data_lines:
        return None
    try:
        value = json.loads("\n".join(data_lines))
    except json.JSONDecodeError as exc:
        raise CoreProtocolError("meguri-core returned invalid SSE JSON") from exc
    if not isinstance(value, dict):
        raise CoreProtocolError("meguri-core event envelope must be an object")
    _assert_protocol_v1(_required_string(value, "protocol_version"))
    event_id = _required_string(value, "event_id")
    event_type = _required_string(value, "type")
    event_turn_id = _required_string(value, "turn_id")
    event_session_id = _required_string(value, "session_id")
    required = value.get("required")
    required_extension = value.get("required_extension")
    replay_policy = value.get("replay_policy", "STATE")
    sequence = value.get("sequence")
    data = value.get("data")
    metadata = value.get("metadata")
    if not isinstance(required, bool):
        raise CoreProtocolError("meguri-core event required flag is invalid")
    if required_extension is not None and (
        not isinstance(required_extension, str)
        or not required_extension
        or required_extension not in supported_extensions
    ):
        raise CoreProtocolError(
            f"unsupported required extension: {required_extension}"
        )
    if replay_policy not in {"STATE", "ONCE", "ALWAYS"}:
        raise CoreProtocolError("meguri-core event replay policy is invalid")
    if not isinstance(sequence, int) or isinstance(sequence, bool) or sequence < 1:
        raise CoreProtocolError("meguri-core event sequence is invalid")
    if not isinstance(data, dict) or not isinstance(metadata, dict):
        raise CoreProtocolError("meguri-core event payload is invalid")
    created_at = value.get("created_at")
    if not isinstance(created_at, str) or not created_at:
        raise CoreProtocolError("meguri-core event creation time is invalid")
    for field in ("trace_id", "source", "created_at", "build_id"):
        if not isinstance(metadata.get(field), str) or not metadata[field]:
            raise CoreProtocolError(
                f"meguri-core event metadata {field} is invalid"
            )
    if event_turn_id != turn_id:
        raise CoreProtocolError("meguri-core event crossed the requested turn scope")
    if event_session_id != session_id:
        raise CoreProtocolError("meguri-core event crossed the requested session scope")
    expected_replay = _expected_replay_policy(event_type, data)
    if event_type in _KNOWN_EVENT_TYPES and replay_policy != expected_replay:
        raise CoreProtocolError(
            f"meguri-core event replay policy mismatch for {event_type}"
        )
    checkpoint = int(state["checkpoint"])
    seen_event_ids: set[str] = state["seen_event_ids"]
    once_event_ids: set[str] = state["once_event_ids"]
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
    if replay_policy == "ONCE":
        if event_id in once_event_ids:
            return None
        once_event_ids.add(event_id)
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


def _canonical_turn_payload(payload: dict[str, Any]) -> dict[str, Any]:
    identity = payload.get("identity")
    if isinstance(identity, dict):
        return dict(payload)
    user_id = str(payload.get("user_id", "") or "legacy-unknown")
    session_id = str(payload.get("session_id", "") or "legacy-session")
    canonical = {
        key: value
        for key, value in payload.items()
        if key not in {"user_id", "client_id", "session_id", "client_capabilities"}
    }
    canonical.update(
        {
            "protocol_version": "1.0",
            "identity": {
                "meguri_user": {"id": user_id},
                "platform_actor": {
                    "platform": "astrbot",
                    "actor_id": user_id,
                },
                "client_instance": {
                    "id": "astrbot-legacy",
                    "profile": "astrbot",
                },
                "session": {"id": session_id},
            },
        }
    )
    return canonical


def _legacy_turn_payload(payload: dict[str, Any]) -> dict[str, Any]:
    user_id, session_id, _client_instance_id = _identity_values(payload)
    legacy = {
        key: value
        for key, value in payload.items()
        if key not in {"protocol_version", "identity"}
    }
    legacy.update(
        {
            "user_id": user_id,
            "client_id": "astrbot",
            "session_id": session_id,
            "client_capabilities": {
                key: _CAPABILITIES[key]
                for key in ("text", "sprite", "voice", "screen_context")
            },
        }
    )
    return legacy


def _identity_values(payload: dict[str, Any]) -> tuple[str, str, str]:
    identity = payload.get("identity")
    if not isinstance(identity, dict):
        raise CoreProtocolError("AstrBot Turn identity must be an object")
    meguri_user = identity.get("meguri_user")
    session = identity.get("session")
    client_instance = identity.get("client_instance")
    if not all(
        isinstance(value, dict)
        for value in (meguri_user, session, client_instance)
    ):
        raise CoreProtocolError("AstrBot Turn identity is incomplete")
    return (
        _required_string(meguri_user, "id"),
        _required_string(session, "id"),
        _required_string(client_instance, "id"),
    )


def _platform_actor_id(payload: dict[str, Any]) -> str:
    identity = payload.get("identity")
    platform_actor = (
        identity.get("platform_actor") if isinstance(identity, dict) else None
    )
    if not isinstance(platform_actor, dict):
        raise CoreProtocolError("AstrBot platform actor identity is missing")
    return _required_string(platform_actor, "actor_id")


def _assert_protocol_v1(version: str) -> None:
    major, separator, minor = version.partition(".")
    if separator != "." or major != "1" or not minor.isdigit():
        raise CoreProtocolError(
            f"meguri-core returned incompatible protocol version: {version}",
            code="UNSUPPORTED_PROTOCOL_MAJOR",
        )


def _expected_replay_policy(
    event_type: str, data: dict[str, Any]
) -> str:
    if event_type.startswith("tts.") or event_type in {
        "voice.requested",
        "audio.ready",
    }:
        return "ONCE"
    if event_type == "semantic.cue" and data.get("channel") in {
        "animation",
        "notification",
    }:
        return "ONCE"
    return "STATE" if event_type in _STATE_EVENT_TYPES else "ALWAYS"


def _string_list(value: Any) -> bool:
    return isinstance(value, list) and all(
        isinstance(item, str) and item for item in value
    )
