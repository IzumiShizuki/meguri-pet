from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from typing import Any

import httpx

from adapters.astrbot.astrbot_plugin_meguri_gateway.client import (
    CoreProtocolError,
    HttpMeguriCoreClient,
    _KNOWN_EVENT_TYPES,
    _TERMINAL_EVENT_TYPES,
    _accept_sse_data,
    _assert_protocol_v1,
    _expected_replay_policy,
)
from adapters.astrbot.astrbot_plugin_meguri_gateway.gateway import _idempotency_key
from adapters.astrbot.astrbot_plugin_meguri_gateway.models import PlatformMessage
from adapters.astrbot.astrbot_plugin_meguri_gateway.turn_checkpoints import (
    TurnCheckpointStore,
)


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "contracts" / "adapter-protocol" / "v1" / "fixtures"
FLOWS = json.loads((FIXTURES / "flows.json").read_text(encoding="utf-8"))
PROFILES = json.loads((FIXTURES / "profiles.json").read_text(encoding="utf-8"))


def event(
    sequence: int,
    event_type: str,
    *,
    event_id: str | None = None,
    replay_policy: str | None = None,
    data: dict[str, Any] | None = None,
) -> dict[str, Any]:
    payload = data or {}
    return {
        "protocol_version": "1.0",
        "event_id": event_id or f"fixture-{sequence}",
        "required": True,
        "replay_policy": replay_policy
        or _expected_replay_policy(event_type, payload),
        "type": event_type,
        "turn_id": "turn-fixture",
        "session_id": PROFILES["astrbot"]["identity"]["session"]["id"],
        "sequence": sequence,
        "created_at": "2026-07-29T00:00:00Z",
        "data": payload,
        "metadata": {
            "trace_id": "trace-fixture",
            "source": "fixture-core",
            "created_at": "2026-07-29T00:00:00Z",
            "build_id": "build-fixture",
        },
    }


def sse(items: list[dict[str, Any]]) -> str:
    return "".join(
        f"id: {item['sequence']}\nevent: {item['type']}\n"
        f"data: {json.dumps(item)}\n\n"
        for item in items
    )


class CanonicalFixtureTests(unittest.TestCase):
    def test_profiles_use_shared_identity_and_isolated_sessions(self):
        identities = [profile["identity"] for profile in PROFILES.values()]
        self.assertEqual(
            {identity["meguri_user"]["id"] for identity in identities},
            {FLOWS["identity"]["shared_meguri_user_id"]},
        )
        self.assertEqual(
            {identity["session"]["id"] for identity in identities},
            set(FLOWS["identity"]["isolated_session_ids"]),
        )

    def test_minor_version_is_accepted_and_major_is_stably_rejected(self):
        _assert_protocol_v1(FLOWS["minor_accepted"])
        with self.assertRaises(CoreProtocolError) as raised:
            _assert_protocol_v1(FLOWS["major_rejected"])
        self.assertEqual(raised.exception.code, "UNSUPPORTED_PROTOCOL_MAJOR")
        self.assertFalse(raised.exception.retryable)

    def test_required_catalog_replay_terminal_cursor_and_once(self):
        state = {
            "checkpoint": 0,
            "seen_event_ids": set(),
            "once_event_ids": set(),
        }
        for index, fixture in enumerate(FLOWS["required_event_catalog"], 1):
            self.assertIn(fixture["type"], _KNOWN_EVENT_TYPES)
            self.assertEqual(
                _expected_replay_policy(fixture["type"], {}),
                fixture["replay_policy"],
            )
            value = event(
                index,
                fixture["type"],
                replay_policy=fixture["replay_policy"],
            )
            self.assertIsNone(
                _accept_sse_data(
                    [json.dumps(value)],
                    turn_id="turn-fixture",
                    session_id=PROFILES["astrbot"]["identity"]["session"]["id"],
                    state=state,
                    supported_extensions=frozenset(),
                )
            )
        self.assertEqual(set(FLOWS["terminal"]), _TERMINAL_EVENT_TYPES)

        once_id = FLOWS["once_replay"]["event_id"]
        state = {
            "checkpoint": FLOWS["cursor_expired"]["snapshot_sequence"],
            "seen_event_ids": {once_id},
            "once_event_ids": {once_id},
        }
        replay = event(
            state["checkpoint"] + 1,
            FLOWS["once_replay"]["type"],
            event_id=once_id,
            replay_policy="ONCE",
        )
        self.assertIsNone(
            _accept_sse_data(
                [json.dumps(replay)],
                turn_id="turn-fixture",
                session_id=PROFILES["astrbot"]["identity"]["session"]["id"],
                state=state,
                supported_extensions=frozenset(),
            )
        )
        self.assertEqual(len(state["once_event_ids"]), 1)

    def test_platform_message_id_produces_stable_scoped_idempotency_key(self):
        message = PlatformMessage(
            platform="qq",
            account_id="bot-1",
            sender_id="qq-10001",
            conversation_id="friend-qq-10001",
            message_id="platform-message-20-7",
            text="hello",
            chat_type="private",
        )
        self.assertEqual(_idempotency_key(message), _idempotency_key(message))
        self.assertEqual(len(_idempotency_key(message)), 64)

    def test_sync_wrapper_returns_the_same_async_turn_result(self):
        profile = PROFILES["astrbot"]
        events = [
            event(1, "turn.started", data={"runtime_state": {}}),
            event(2, "semantic.completed", data={"reply": "sync wrapper"}),
            event(3, "text.completed", data={"text": "sync wrapper"}),
            event(4, "turn.completed"),
        ]

        async def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path == "/v1/hello":
                return httpx.Response(200, json={
                    "selected_protocol_version": "1.0",
                    "server_capabilities_revision": "fixture-sync-1",
                    "server_capabilities": profile["capabilities"],
                    "effective_capabilities": profile["capabilities"],
                    "granted_permissions": profile["permissions"],
                    "supported_extensions": [],
                })
            if request.url.path == "/v1/turns":
                return httpx.Response(202, json={
                    "protocol_version": "1.0",
                    "turn_id": "turn-fixture",
                    "session_id": profile["identity"]["session"]["id"],
                    "build_id": "build-fixture",
                    "status": "accepted",
                })
            return httpx.Response(200, text=sse(events))

        client = HttpMeguriCoreClient(transport=httpx.MockTransport(handler))
        result = client.respond_sync({
            "identity": profile["identity"],
            "message": "hello",
            "attachments": [],
        }, idempotency_key="platform-message-sync")
        self.assertEqual(result["response"]["reply"], "sync wrapper")


class AstrBotCanonicalTransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_async_turn_uses_canonical_profile_and_checkpoint(self):
        profile = PROFILES["astrbot"]
        requests: list[httpx.Request] = []
        events = [
            event(1, "turn.started", data={"runtime_state": {}}),
            event(2, "semantic.completed", data={"reply": "same turn"}),
            event(3, "text.completed", data={"text": "same turn"}),
            event(4, "turn.completed"),
        ]

        async def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            if request.url.path == "/v1/hello":
                return httpx.Response(
                    200,
                    json={
                        "selected_protocol_version": "1.0",
                        "server_capabilities_revision": "fixture-1",
                        "server_capabilities": profile["capabilities"],
                        "effective_capabilities": profile["capabilities"],
                        "granted_permissions": profile["permissions"],
                        "supported_extensions": [],
                    },
                )
            if request.url.path == "/v1/turns":
                return httpx.Response(
                    202,
                    json={
                        "protocol_version": "1.0",
                        "turn_id": "turn-fixture",
                        "session_id": profile["identity"]["session"]["id"],
                        "build_id": "build-fixture",
                        "status": "accepted",
                    },
                )
            return httpx.Response(200, text=sse(events))

        with tempfile.TemporaryDirectory() as temporary:
            client = HttpMeguriCoreClient(
                transport=httpx.MockTransport(handler),
                checkpoint_store=TurnCheckpointStore(Path(temporary) / "turns.json"),
            )
            self.addAsyncCleanup(client.close)
            result = await client.respond(
                {
                    "identity": profile["identity"],
                    "message": "hello",
                    "attachments": [],
                },
                idempotency_key="platform-message-20-7",
            )
            self.assertEqual(result["response"]["reply"], "same turn")
            hello = json.loads(requests[0].content)
            created = json.loads(requests[1].content)
            self.assertEqual(hello["identity"], profile["identity"])
            self.assertEqual(created["identity"], profile["identity"])
            self.assertEqual(
                requests[1].headers["idempotency-key"],
                "platform-message-20-7",
            )

    async def test_stable_protocol_errors_preserve_code_and_retryability(self):
        status_by_code = {
            "UNAUTHORIZED": 401,
            "CONFLICT": 409,
            "TURN_TERMINAL": 409,
            "CURSOR_EXPIRED": 410,
            "UNSUPPORTED_PROTOCOL_MAJOR": 400,
        }
        for fixture in FLOWS["stable_errors"]:
            response = httpx.Response(
                status_by_code[fixture["code"]],
                request=httpx.Request("POST", "http://localhost/v1/turns"),
                json={
                    "protocol_version": "1.0",
                    "error": {
                        "code": fixture["code"],
                        "message": fixture["code"].lower(),
                        "retryable": fixture["retryable"],
                        "details": {"fixture": True},
                    },
                },
            )
            with self.assertRaises(CoreProtocolError) as raised:
                HttpMeguriCoreClient._raise_for_status(response)
            self.assertEqual(raised.exception.code, fixture["code"])
            self.assertEqual(raised.exception.retryable, fixture["retryable"])


if __name__ == "__main__":
    unittest.main()
