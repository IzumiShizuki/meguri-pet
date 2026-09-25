import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import httpx

from adapters.astrbot.astrbot_plugin_meguri_gateway.turn_checkpoints import TurnCheckpointStore

from adapters.astrbot.astrbot_plugin_meguri_gateway.client import (
    CoreProtocolError,
    HttpMeguriCoreClient,
    _expected_replay_policy,
)
from adapters.astrbot.astrbot_plugin_meguri_gateway.bridge import (
    MessageRoutePolicy,
    is_meguri_command,
    platform_message_from_event,
)
from adapters.astrbot.astrbot_plugin_meguri_gateway.commands import parse_command
from adapters.astrbot.astrbot_plugin_meguri_gateway.gateway import MeguriGateway
from adapters.astrbot.astrbot_plugin_meguri_gateway.identity import IdentityBindingStore
from adapters.astrbot.astrbot_plugin_meguri_gateway.models import PlatformMessage
from adapters.astrbot.astrbot_plugin_meguri_gateway.relay import (
    HttpMeguriRelayClient,
    RemoteDevice,
    RemoteTask,
    RemoteTaskDraft,
)


def platform_message(**overrides):
    values = {
        "platform": "QQOfficial",
        "account_id": "bot-account",
        "sender_id": "platform-user",
        "conversation_id": "platform-user",
        "message_id": "message-1",
        "text": "hello Meguri",
        "chat_type": "private",
    }
    values.update(overrides)
    return PlatformMessage(**values)


def hello_response():
    return {
        "selected_protocol_version": "1.1",
        "server_capabilities_revision": "astrbot-caps-1",
        "server_capabilities": {
            "text": True,
            "voice": False,
            "sprite": True,
            "screen_context": False,
            "formal_memory": True,
            "sse": True,
        },
        "effective_capabilities": {
            "text": True,
            "voice": False,
            "sprite": True,
            "screen_context": False,
            "formal_memory": False,
            "sse": True,
        },
        "granted_permissions": {
            "screen_read": False,
            "microphone": False,
            "audio_playback": False,
            "notifications": False,
            "formal_memory_write": False,
        },
        "supported_extensions": [],
    }


class FakeCoreClient:
    def __init__(self, fail=False, reply="Meguri reply"):
        self.fail = fail
        self.reply = reply
        self.respond_payloads = []
        self.overrides = []
        self.cleared = []

    async def respond(self, payload, *, idempotency_key=None):
        if self.fail:
            raise CoreProtocolError("offline")
        self.respond_payloads.append(payload)
        self.idempotency_key = idempotency_key
        return {
            "turn_id": "turn-test",
            "build_id": "meguri-build-test",
            "response": {
                "reply": self.reply,
                "expression_tag": "neutral",
                "expression_intensity": "low",
                "voice_style": "neutral",
                "memory_candidates": [],
            },
            "runtime_state": {
                "client_id": "astrbot",
                "mode": "work",
                "relationship_profile": "sibling",
                "outfit_code": "01",
                "local_time": "2026-07-23T10:00:00+08:00",
                "is_holiday": False,
                "voice_enabled": False,
                "screen_context_enabled": False,
                "allowed_expression_tags": ["neutral"],
            },
            "expression": {
                "expression_tag": "neutral",
                "expression_intensity": "low",
                "outfit_code": "01",
                "expression_code": "001",
                "sprite_file": "ce01001l.png",
            },
        }

    async def runtime_state(self, user_id, session_id):
        return {"mode": "work", "outfit_code": "01", "relationship_profile": "sibling"}

    async def set_override(self, user_id, override):
        self.overrides.append((user_id, override))
        return {"status": "ok"}

    async def clear_override(self, scope):
        self.cleared.append(scope)
        return {"status": "cleared"}


class FakeRelayClient:
    def __init__(self):
        self.calls = []

    async def list_devices(self, user_id, session_id):
        self.calls.append(("devices", user_id, session_id))
        return [
            RemoteDevice(
                device_id="home-main",
                label="Home Main",
                status="online",
                capabilities=["codex", "test"],
            )
        ]

    async def preview_task(self, **kwargs):
        self.calls.append(("preview", kwargs))
        return RemoteTaskDraft(
            draft_id="draft-1",
            target_device=RemoteDevice(
                device_id="home-main",
                label="Home Main",
                status="online",
            ),
            summary="Run repository tests",
            confirmation_code="7K3P",
            expires_at="2026-07-22T12:00:00Z",
            permissions=["read", "test"],
        )

    async def confirm_task(self, **kwargs):
        self.calls.append(("confirm", kwargs))
        return self._task("queued")

    async def get_task(self, **kwargs):
        self.calls.append(("task", kwargs))
        return self._task("running")

    async def cancel_task(self, **kwargs):
        self.calls.append(("cancel", kwargs))
        return self._task("cancelled")

    @staticmethod
    def _task(status):
        return RemoteTask(
            task_id="task-1",
            status=status,
            target_device_id="home-main",
            summary="Run repository tests",
        )


class IdentityBindingTests(unittest.TestCase):
    def test_private_and_group_sessions_are_isolated(self):
        bindings = IdentityBindingStore("test-salt")
        private = bindings.resolve(platform_message())
        group = bindings.resolve(
            platform_message(chat_type="group", conversation_id="group-1")
        )
        self.assertEqual(private.meguri_user_id, group.meguri_user_id)
        self.assertNotEqual(private.session_id, group.session_id)
        self.assertNotIn("platform-user", private.meguri_user_id)
        self.assertNotIn("platform-user", private.session_id)

    def test_explicit_binding_enables_cross_platform_user_identity(self):
        bindings = IdentityBindingStore("test-salt")
        bindings.bind("QQOfficial", "qq-user", "meguri-user-1")
        bindings.bind("Telegram", "telegram-user", "meguri-user-1")
        qq = bindings.resolve(platform_message(sender_id="qq-user"))
        telegram = bindings.resolve(
            platform_message(platform="Telegram", sender_id="telegram-user")
        )
        self.assertEqual(qq.meguri_user_id, telegram.meguri_user_id)
        self.assertNotEqual(qq.session_id, telegram.session_id)
        self.assertTrue(qq.formal_memory_allowed)
        self.assertTrue(telegram.formal_memory_allowed)

    def test_bot_accounts_do_not_share_implicit_identity(self):
        bindings = IdentityBindingStore("test-salt")
        first = bindings.resolve(platform_message(account_id="bot-a"))
        second = bindings.resolve(platform_message(account_id="bot-b"))
        self.assertNotEqual(first.meguri_user_id, second.meguri_user_id)
        self.assertFalse(first.formal_memory_allowed)
        self.assertFalse(second.formal_memory_allowed)


class CommandParsingTests(unittest.TestCase):
    def test_override_duration_is_timezone_aware(self):
        now = datetime(2026, 7, 13, tzinfo=timezone.utc)
        command = parse_command("/meguri mode private 2h", now=now)
        self.assertEqual(command.action, "set_override")
        self.assertEqual(command.override["mode"], "private")
        self.assertEqual(command.override["expires_at"], "2026-07-13T02:00:00+00:00")

    def test_disabled_outfit_falls_back_to_help(self):
        self.assertEqual(parse_command("/meguri outfit 07").action, "help")

    def test_command_prefix_is_exact_and_remote_run_preserves_prompt(self):
        self.assertIsNone(parse_command("/megurix status"))
        command = parse_command(
            "/meguri run --device home-main 检查今天的修改并运行测试"
        )
        self.assertEqual(command.action, "run")
        self.assertEqual(command.target_device_id, "home-main")
        self.assertEqual(command.text, "检查今天的修改并运行测试")

    def test_override_duration_is_bounded(self):
        self.assertEqual(parse_command("/meguri mode work 8d").action, "help")
        huge = "9" * 1000
        self.assertEqual(parse_command(f"/meguri mode work {huge}d").action, "help")

    def test_remote_identifiers_reject_path_segments(self):
        self.assertEqual(
            parse_command("/meguri confirm ../../admin ABCD").action,
            "help",
        )
        self.assertEqual(
            parse_command("/meguri run --device ../other do something").action,
            "help",
        )


class GatewayTests(unittest.IsolatedAsyncioTestCase):
    async def test_gateway_builds_canonical_four_part_identity(self):
        core = FakeCoreClient()
        reply = await MeguriGateway(
            core,
            IdentityBindingStore("test-salt"),
            reply_format="zh_ja_pairs",
        ).handle(
            platform_message()
        )
        self.assertEqual(reply.text, "Meguri reply")
        payload = core.respond_payloads[0]
        identity = payload["identity"]
        self.assertEqual(payload["protocol_version"], "1.0")
        self.assertEqual(identity["client_instance"]["profile"], "astrbot")
        self.assertEqual(identity["platform_actor"]["platform"], "QQOfficial")
        self.assertNotIn("platform-user", identity["platform_actor"]["actor_id"])
        self.assertNotIn("bot-account", identity["client_instance"]["id"])
        self.assertEqual(identity["session"]["id"], reply.metadata["session_id"])
        self.assertEqual(core.respond_payloads[0]["reply_format"], "zh_ja_pairs")
        self.assertFalse(core.respond_payloads[0]["formal_memory_allowed"])
        render_payload = reply.metadata["meguri_render_payload"]
        self.assertEqual(render_payload["runtime_state"]["outfit_code"], "01")
        self.assertEqual(render_payload["expression"]["sprite_file"], "ce01001l.png")

    async def test_bilingual_reply_is_preserved_for_text_and_renderer_payload(self):
        bilingual = "【今天也辛苦了。】\n【今日もお疲れさま。】"
        core = FakeCoreClient(reply=bilingual)
        reply = await MeguriGateway(
            core,
            IdentityBindingStore("test-salt"),
            reply_format="zh_ja_pairs",
        ).handle(platform_message())
        self.assertEqual(reply.text, bilingual)
        self.assertEqual(
            reply.metadata["meguri_render_payload"]["response"]["reply"],
            bilingual,
        )

    async def test_commands_do_not_enter_chat_pipeline(self):
        core = FakeCoreClient()
        gateway = MeguriGateway(core, IdentityBindingStore("test-salt"))
        updated = await gateway.handle(
            platform_message(message_id="message-1", text="/meguri relation lover 2h")
        )
        status = await gateway.handle(
            platform_message(message_id="message-2", text="/meguri status")
        )
        reset = await gateway.handle(
            platform_message(message_id="message-3", text="/meguri reset")
        )
        self.assertTrue(updated.command_handled)
        self.assertIn("mode=work", status.text)
        self.assertTrue(reset.command_handled)
        self.assertEqual(core.respond_payloads, [])
        self.assertEqual(len(core.overrides), 1)
        self.assertEqual(len(core.cleared), 1)

    async def test_core_failure_returns_fast_degraded_reply(self):
        core = FakeCoreClient(fail=True)
        gateway = MeguriGateway(core, IdentityBindingStore("test-salt"))
        reply = await gateway.handle(platform_message())
        self.assertTrue(reply.degraded)
        self.assertIn("暂时不可用", reply.text)
        self.assertEqual(reply.metadata["protocol_error"]["code"], "INTERNAL")
        self.assertFalse(reply.metadata["protocol_error"]["retryable"])
        core.fail = False
        retried = await gateway.handle(platform_message())
        self.assertFalse(retried.ignored)
        self.assertEqual(retried.text, "Meguri reply")

    async def test_duplicate_platform_delivery_is_ignored(self):
        gateway = MeguriGateway(FakeCoreClient(), IdentityBindingStore("test-salt"))
        first = await gateway.handle(platform_message())
        duplicate = await gateway.handle(platform_message())
        self.assertFalse(first.ignored)
        self.assertTrue(duplicate.ignored)

    async def test_message_ids_are_scoped_to_the_source_conversation(self):
        core = FakeCoreClient()
        gateway = MeguriGateway(core, IdentityBindingStore("test-salt"))
        first = await gateway.handle(
            platform_message(
                message_id="shared-id",
                conversation_id="conversation-a",
                unified_msg_origin="qq:private:a",
            )
        )
        second = await gateway.handle(
            platform_message(
                message_id="shared-id",
                sender_id="platform-user-b",
                conversation_id="conversation-b",
                unified_msg_origin="qq:private:b",
            )
        )
        self.assertFalse(first.ignored)
        self.assertFalse(second.ignored)

    async def test_remote_commands_require_an_authorized_sender(self):
        relay = FakeRelayClient()
        gateway = MeguriGateway(
            FakeCoreClient(),
            IdentityBindingStore("test-salt"),
            relay=relay,
        )
        reply = await gateway.handle(platform_message(text="/meguri devices"))
        self.assertIn("没有远程开发权限", reply.text)
        self.assertEqual(relay.calls, [])

    async def test_remote_preview_confirmation_status_and_cancel_flow(self):
        relay = FakeRelayClient()
        gateway = MeguriGateway(
            FakeCoreClient(),
            IdentityBindingStore("test-salt"),
            relay=relay,
            remote_authorizer=lambda message: message.sender_is_admin,
        )
        preview = await gateway.handle(
            platform_message(
                message_id="remote-1",
                text="/meguri run --device home-main 运行测试",
                sender_is_admin=True,
                unified_msg_origin="qq:private:user",
            )
        )
        confirmed = await gateway.handle(
            platform_message(
                message_id="remote-2",
                text="/meguri confirm draft-1 7K3P",
                sender_is_admin=True,
            )
        )
        status = await gateway.handle(
            platform_message(
                message_id="remote-3",
                text="/meguri task task-1",
                sender_is_admin=True,
            )
        )
        cancelled = await gateway.handle(
            platform_message(
                message_id="remote-4",
                text="/meguri cancel task-1",
                sender_is_admin=True,
            )
        )
        self.assertIn("确认码：7K3P", preview.text)
        self.assertIn("queued", confirmed.text)
        self.assertIn("running", status.text)
        self.assertIn("cancelled", cancelled.text)
        preview_call = next(call for call in relay.calls if call[0] == "preview")
        self.assertEqual(len(preview_call[1]["source_message_id"]), 64)
        self.assertNotEqual(preview_call[1]["source_message_id"], "remote-1")


class HttpCoreClientTests(unittest.IsolatedAsyncioTestCase):
    async def test_http_client_rejects_wildcard_and_public_urls(self):
        with self.assertRaises(ValueError):
            HttpMeguriCoreClient("http://0.0.0.0:8100")
        with self.assertRaises(ValueError):
            HttpMeguriCoreClient("http://111.228.35.186:8100")
        with self.assertRaises(ValueError):
            HttpMeguriCoreClient("ftp://localhost:8100")
        with self.assertRaises(ValueError):
            HttpMeguriCoreClient("localhost:8100")

    async def test_http_client_validates_object_response(self):
        async def handler(request):
            return httpx.Response(200, json=["not-an-object"])

        client = HttpMeguriCoreClient(
            transport=httpx.MockTransport(handler),
            async_turns_enabled=False,
        )
        self.addAsyncCleanup(client.close)
        with self.assertRaises(CoreProtocolError):
            await client.respond({"message": "test"})

    async def test_http_client_reuses_authenticated_identity_headers(self):
        requests = []

        async def handler(request):
            requests.append(request)
            return httpx.Response(200, json={"response": {"reply": "ok"}})

        client = HttpMeguriCoreClient(
            transport=httpx.MockTransport(handler),
            tenant_id="tenant-test",
            shared_token="shared-test",
            async_turns_enabled=False,
        )
        self.addAsyncCleanup(client.close)
        await client.respond({"user_id": "user-1", "session_id": "session-1"})
        self.assertEqual(requests[0].headers["authorization"], "Bearer shared-test")
        self.assertEqual(requests[0].headers["x-meguri-tenant-id"], "tenant-test")
        self.assertEqual(requests[0].headers["x-meguri-client-id"], "astrbot")

    async def test_http_client_uses_turn_sse_and_deduplicates_event_ids(self):
        requests = []

        def event(sequence, kind, data, *, event_id=None, required=False):
            return {
                "protocol_version": "1.0",
                "event_id": event_id or f"event-{sequence}",
                "required": required,
                "replay_policy": _expected_replay_policy(kind, data),
                "type": kind,
                "turn_id": "turn-1",
                "session_id": "session-1",
                "sequence": sequence,
                "created_at": "2026-07-28T00:00:00Z",
                "data": data,
                "metadata": {
                    "trace_id": "trace-1",
                    "source": "meguri-core",
                    "created_at": "2026-07-28T00:00:00Z",
                    "build_id": "build-1",
                },
            }

        envelopes = [
            event(
                1,
                "turn.started",
                {"runtime_state": {"mode": "work", "outfit_code": "01"}},
                required=True,
            ),
            event(
                2,
                "semantic.completed",
                {
                    "reply": "streamed reply",
                    "expression_tag": "happy",
                    "expression_intensity": "medium",
                    "voice_style": "soft",
                    "memory_candidates": [],
                },
                event_id="semantic-once",
            ),
            event(
                2,
                "semantic.completed",
                {"reply": "must not replace the first delivery"},
                event_id="semantic-once",
            ),
            event(3, "text.completed", {"text": "streamed reply"}, required=True),
            event(
                4,
                "expression.cue",
                {
                    "expression_tag": "happy",
                    "expression_intensity": "medium",
                    "outfit_code": "01",
                },
            ),
            event(5, "memory.write.completed", {"status": "pending"}),
            event(6, "turn.completed", {"reply": "streamed reply"}, required=True),
        ]
        stream = "".join(
            f"id: {item['sequence']}\nevent: {item['type']}\n"
            f"data: {json.dumps(item)}\n\n"
            for item in envelopes
        )

        async def handler(request):
            requests.append(request)
            if request.url.path == "/v1/hello":
                return httpx.Response(200, json=hello_response())
            if request.method == "POST" and request.url.path == "/v1/turns":
                return httpx.Response(
                    202,
                    json={
                        "turn_id": "turn-1",
                        "session_id": "session-1",
                        "build_id": "build-1",
                        "status": "accepted",
                    },
                )
            if request.method == "GET" and request.url.path.endswith("/events"):
                return httpx.Response(200, text=stream, headers={"Content-Type": "text/event-stream"})
            return httpx.Response(500)

        client = HttpMeguriCoreClient(
            transport=httpx.MockTransport(handler),
            tenant_id="tenant-test",
            shared_token="shared-test",
        )
        self.addAsyncCleanup(client.close)
        result = await client.respond(
            {
                "user_id": "user-1",
                "client_id": "astrbot",
                "session_id": "session-1",
                "message": "hello",
            },
            idempotency_key="platform-message-1",
        )

        self.assertEqual(result["response"]["reply"], "streamed reply")
        self.assertEqual(result["runtime_state"]["mode"], "work")
        self.assertEqual(result["expression"]["expression_tag"], "happy")
        self.assertEqual(result["memory_status"], "pending")
        self.assertEqual(requests[1].headers["idempotency-key"], "platform-message-1")
        hello_body = json.loads(requests[0].content)
        turn_body = json.loads(requests[1].content)
        self.assertEqual(hello_body["protocol_versions"], ["1.1", "1.0"])
        self.assertFalse(hello_body["capabilities"]["voice"])
        self.assertTrue(hello_body["capabilities"]["sprite"])
        self.assertFalse(hello_body["permissions"]["screen_read"])
        self.assertEqual(turn_body["protocol_version"], "1.1")
        self.assertEqual(turn_body["identity"]["client_instance"]["profile"], "astrbot")
        self.assertNotIn("user_id", turn_body)
        self.assertEqual(
            requests[2].headers["meguri-protocol-version"],
            "1.1",
        )
        self.assertEqual([request.url.path for request in requests], [
            "/v1/hello",
            "/v1/turns",
            "/v1/sessions/session-1/events",
        ])

    async def test_http_client_resumes_persisted_turn_checkpoint_after_restart(self):
        def event(sequence, kind, data, *, required=False):
            return {
                "protocol_version": "1.0",
                "event_id": f"persisted-event-{sequence}",
                "required": required,
                "replay_policy": _expected_replay_policy(kind, data),
                "type": kind,
                "turn_id": "turn-persisted",
                "session_id": "session-persisted",
                "sequence": sequence,
                "created_at": "2026-07-28T00:00:00Z",
                "data": data,
                "metadata": {
                    "trace_id": "trace-persisted",
                    "source": "meguri-core",
                    "created_at": "2026-07-28T00:00:00Z",
                    "build_id": "build-persisted",
                },
            }

        initial = [
            event(
                1,
                "turn.started",
                {"runtime_state": {"mode": "work", "outfit_code": "01"}},
                required=True,
            ),
            event(
                2,
                "semantic.completed",
                {
                    "reply": "persisted reply",
                    "expression_tag": "happy",
                    "expression_intensity": "medium",
                    "voice_style": "soft",
                    "memory_candidates": [],
                },
            ),
        ]
        remaining = [
            event(3, "text.completed", {"text": "persisted reply"}, required=True),
            event(
                4,
                "expression.cue",
                {"expression_tag": "happy", "expression_intensity": "medium", "outfit_code": "01"},
            ),
            event(5, "memory.write.completed", {"status": "pending"}),
            event(6, "turn.completed", {}, required=True),
        ]

        def stream(events):
            return "".join(
                f"id: {item['sequence']}\nevent: {item['type']}\n"
                f"data: {json.dumps(item)}\n\n"
                for item in events
            )

        with tempfile.TemporaryDirectory() as temp:
            checkpoint = TurnCheckpointStore(Path(temp) / "turns.json")
            first_get = True

            async def first_handler(request):
                nonlocal first_get
                if request.url.path == "/v1/hello":
                    return httpx.Response(200, json=hello_response())
                if request.method == "POST":
                    return httpx.Response(202, json={
                        "turn_id": "turn-persisted",
                        "session_id": "session-persisted",
                        "build_id": "build-persisted",
                        "status": "accepted",
                    })
                if first_get:
                    first_get = False
                    return httpx.Response(200, text=stream(initial))
                return httpx.Response(200, text="")

            first = HttpMeguriCoreClient(
                transport=httpx.MockTransport(first_handler),
                checkpoint_store=checkpoint,
            )
            self.addAsyncCleanup(first.close)
            with self.assertRaises(CoreProtocolError):
                await first.respond({
                    "user_id": "user-1",
                    "client_id": "astrbot",
                    "session_id": "session-persisted",
                    "message": "hello",
                })
            negotiation = checkpoint.load_negotiation("astrbot-legacy")
            self.assertEqual(
                negotiation["selected_protocol_version"],
                "1.1",
            )
            self.assertEqual(
                negotiation["server_capabilities_revision"],
                "astrbot-caps-1",
            )

            requested_after = []

            async def second_handler(request):
                if request.url.path == "/v1/hello":
                    return httpx.Response(200, json=hello_response())
                if request.method == "POST":
                    return httpx.Response(202, json={
                        "turn_id": "turn-persisted",
                        "session_id": "session-persisted",
                        "build_id": "build-persisted",
                        "status": "accepted",
                    })
                requested_after.append(request.url.params.get("after_sequence"))
                return httpx.Response(200, text=stream(remaining))

            second = HttpMeguriCoreClient(
                transport=httpx.MockTransport(second_handler),
                checkpoint_store=TurnCheckpointStore(Path(temp) / "turns.json"),
            )
            self.addAsyncCleanup(second.close)
            result = await second.respond({
                "user_id": "user-1",
                "client_id": "astrbot",
                "session_id": "session-persisted",
                "message": "hello",
            })

            self.assertEqual(requested_after, ["2"])
            self.assertEqual(result["response"]["reply"], "persisted reply")
            self.assertEqual(result["memory_status"], "pending")

    async def test_http_client_reconciles_terminal_snapshot_after_sse_reconnect_exhaustion(self):
        requested_after = []
        snapshot_requests = 0

        def event(sequence, kind, data, *, required=False):
            return {
                "protocol_version": "1.0",
                "event_id": f"reconnect-event-{sequence}",
                "required": required,
                "replay_policy": _expected_replay_policy(kind, data),
                "type": kind,
                "turn_id": "turn-reconnect",
                "session_id": "session-reconnect",
                "sequence": sequence,
                "created_at": "2026-08-01T00:00:00Z",
                "data": data,
                "metadata": {
                    "trace_id": "trace-reconnect",
                    "source": "meguri-core",
                    "created_at": "2026-08-01T00:00:00Z",
                    "build_id": "build-reconnect",
                },
            }

        def stream(items):
            return "".join(
                f"id: {item['sequence']}\nevent: {item['type']}\n"
                f"data: {json.dumps(item)}\n\n"
                for item in items
            )

        async def handler(request):
            nonlocal snapshot_requests
            if request.url.path == "/v1/hello":
                return httpx.Response(200, json=hello_response())
            if request.url.path == "/v1/turns":
                return httpx.Response(202, json={
                    "turn_id": "turn-reconnect",
                    "session_id": "session-reconnect",
                    "build_id": "build-reconnect",
                    "status": "accepted",
                })
            if request.url.path.endswith("/events"):
                requested_after.append(request.url.params.get("after_sequence"))
                replay = {
                    "0": [event(1, "turn.started", {
                        "runtime_state": {"mode": "work", "outfit_code": "01"},
                    }, required=True)],
                    "1": [event(2, "text.delta", {"delta": "partial"})],
                    "2": [],
                }
                return httpx.Response(200, text=stream(replay[requested_after[-1]]))
            if request.url.path.endswith("/snapshot"):
                snapshot_requests += 1
                return httpx.Response(200, json={
                    "protocol_version": "1.0",
                    "session_id": "session-reconnect",
                    "sequence": 3,
                    "turns": [{
                        "turn_id": "turn-reconnect",
                        "status": "completed",
                        "text": "completed while SSE was disconnected",
                    }],
                    "processed_event_ids": [],
                    "processed_once_event_ids": [],
                    "created_at": "2026-08-01T00:00:00Z",
                })
            return httpx.Response(500)

        client = HttpMeguriCoreClient(transport=httpx.MockTransport(handler))
        self.addAsyncCleanup(client.close)
        result = await client.respond({
            "user_id": "user-1",
            "client_id": "astrbot",
            "session_id": "session-reconnect",
            "message": "hello",
        })

        self.assertEqual(requested_after, ["0", "1", "2"])
        self.assertEqual(snapshot_requests, 1)
        self.assertEqual(result["response"]["reply"], "completed while SSE was disconnected")

    async def test_http_client_restores_410_snapshot_then_resumes(self):
        requests = []

        def event(sequence, kind, *, required=False):
            return {
                "protocol_version": "1.9",
                "event_id": f"snapshot-event-{sequence}",
                "required": required,
                "replay_policy": "STATE",
                "type": kind,
                "turn_id": "turn-snapshot",
                "session_id": "session-snapshot",
                "sequence": sequence,
                "created_at": "2026-07-28T00:00:00Z",
                "data": {"future_optional": True},
                "metadata": {
                    "trace_id": "trace-snapshot",
                    "source": "meguri-core",
                    "created_at": "2026-07-28T00:00:00Z",
                    "build_id": "build-snapshot",
                    "future_optional": {"ignored": True},
                },
                "future_optional": {"ignored": True},
            }

        stream = "".join(
            f"id: {item['sequence']}\nevent: {item['type']}\n"
            f"data: {json.dumps(item)}\n\n"
            for item in [
                event(6, "future.optional"),
                event(7, "turn.completed", required=True),
            ]
        )
        events_attempt = 0

        async def handler(request):
            nonlocal events_attempt
            requests.append(request)
            if request.url.path == "/v1/hello":
                return httpx.Response(200, json=hello_response())
            if request.url.path == "/v1/turns":
                return httpx.Response(202, json={
                    "protocol_version": "1.1",
                    "turn_id": "turn-snapshot",
                    "session_id": "session-snapshot",
                    "build_id": "build-snapshot",
                    "status": "accepted",
                })
            if request.url.path.endswith("/snapshot"):
                return httpx.Response(200, json={
                    "protocol_version": "1.8",
                    "session_id": "session-snapshot",
                    "sequence": 5,
                    "turns": [{
                        "turn_id": "turn-snapshot",
                        "status": "running",
                        "text": "restored text",
                    }],
                    "processed_event_ids": ["state-before-snapshot"],
                    "processed_once_event_ids": ["tts-once-before-snapshot"],
                    "runtime_state": {"mode": "work", "outfit_code": "01"},
                    "created_at": "2026-07-28T00:00:00Z",
                    "future_optional": {"ignored": True},
                })
            events_attempt += 1
            if events_attempt == 1:
                return httpx.Response(410, json={
                    "protocol_version": "1.1",
                    "error": {
                        "code": "CURSOR_EXPIRED",
                        "message": "cursor expired",
                        "retryable": True,
                    },
                })
            return httpx.Response(200, text=stream)

        client = HttpMeguriCoreClient(transport=httpx.MockTransport(handler))
        self.addAsyncCleanup(client.close)
        result = await client.respond({
            "user_id": "user-1",
            "client_id": "astrbot",
            "session_id": "session-snapshot",
            "message": "hello",
        })
        self.assertEqual(result["response"]["reply"], "restored text")
        event_requests = [
            request for request in requests if request.url.path.endswith("/events")
        ]
        self.assertEqual(
            [request.url.params.get("after_sequence") for request in event_requests],
            ["0", "5"],
        )


class HttpRelayClientTests(unittest.IsolatedAsyncioTestCase):
    async def test_relay_always_requires_token_and_remote_requires_https(self):
        with self.assertRaises(ValueError):
            HttpMeguriRelayClient("http://127.0.0.1:18100")
        with self.assertRaises(ValueError):
            HttpMeguriRelayClient("http://relay.example.com", token="test-token")
        with self.assertRaises(ValueError):
            HttpMeguriRelayClient("https://relay.example.com")

    async def test_preview_uses_identity_and_idempotency_headers(self):
        requests = []

        async def handler(request):
            requests.append(request)
            return httpx.Response(
                200,
                json={
                    "draft_id": "draft-1",
                    "target_device": {
                        "device_id": "home-main",
                        "label": "Home Main",
                        "status": "online",
                    },
                    "summary": "Run tests",
                    "confirmation_code": "7K3P",
                    "expires_at": "2026-07-22T12:00:00Z",
                },
            )

        client = HttpMeguriRelayClient(
            "http://127.0.0.1:18100",
            token="relay-test",
            transport=httpx.MockTransport(handler),
        )
        self.addAsyncCleanup(client.close)
        await client.preview_task(
            user_id="user-1",
            session_id="session-1",
            prompt="Run tests",
            target_device_id="home-main",
            source_message_id="message-1",
        )
        self.assertEqual(requests[0].headers["idempotency-key"], "message-1")
        self.assertEqual(requests[0].headers["x-meguri-user-id"], "user-1")
        self.assertEqual(requests[0].headers["authorization"], "Bearer relay-test")
        self.assertNotIn("reply_origin", json.loads(requests[0].content))


class FakeAstrBotEvent:
    class Message:
        message_id = "event-1"
        timestamp = 123

    message_obj = Message()
    unified_msg_origin = "QQOfficial:FriendMessage:user-1"

    def __init__(self, text="/meguri status", private=True, admin=False):
        self.text = text
        self.private = private
        self.admin = admin

    def get_platform_name(self):
        return "QQOfficial"

    def get_self_id(self):
        return "bot-account"

    def get_sender_id(self):
        return "user-1"

    def is_private_chat(self):
        return self.private

    def get_session_id(self):
        return self.unified_msg_origin

    def get_group_id(self):
        return None if self.private else "group-1"

    def get_message_str(self):
        return self.text

    def is_admin(self):
        return self.admin


class AstrBotBridgeTests(unittest.TestCase):
    def test_event_conversion_and_route_policy(self):
        message = platform_message_from_event(FakeAstrBotEvent(admin=True))
        self.assertEqual(message.platform, "QQOfficial")
        self.assertEqual(message.chat_type, "private")
        self.assertTrue(message.sender_is_admin)
        self.assertTrue(is_meguri_command(message.text))
        policy = MessageRoutePolicy(
            allowed_senders=frozenset({"QQOfficial:bot-account:user-1"})
        )
        self.assertTrue(policy.should_route(message))
        self.assertTrue(policy.is_allowed(message))

    def test_group_messages_are_opt_in_and_must_be_explicit_by_default(self):
        explicit = platform_message_from_event(FakeAstrBotEvent(private=False))
        ordinary = platform_message_from_event(
            FakeAstrBotEvent(text="hello", private=False)
        )
        policy = MessageRoutePolicy(allow_group_messages=True)
        self.assertTrue(policy.should_route(explicit))
        self.assertFalse(policy.should_route(ordinary))

    def test_group_messages_can_route_to_meguri_by_default(self):
        ordinary = platform_message_from_event(
            FakeAstrBotEvent(text="hello", private=False)
        )
        policy = MessageRoutePolicy(
            allow_group_messages=True,
            route_all_group_messages=True,
        )
        self.assertTrue(policy.should_route(ordinary))

    def test_non_text_event_is_left_for_other_astrbot_handlers(self):
        self.assertIsNone(platform_message_from_event(FakeAstrBotEvent(text="  ")))

    def test_plugin_commands_are_released_before_meguri_routes(self):
        policy = MessageRoutePolicy(
            route_all_private_messages=True,
            passthrough_commands=("jrlp", "查老婆"),
        )

        # Another plugin owns these messages, including with trailing arguments.
        self.assertTrue(policy.matches_passthrough("jrlp"))
        self.assertTrue(policy.matches_passthrough("JRLP"))
        self.assertTrue(policy.matches_passthrough("查老婆 @123456"))
        # Prefix matching must not swallow a longer unrelated word.
        self.assertFalse(policy.matches_passthrough("jrlpx"))
        self.assertFalse(policy.matches_passthrough("今天天气怎么样"))
        # An explicit /meguri invocation always stays with Meguri.
        self.assertFalse(policy.matches_passthrough("/meguri chat jrlp"))

    def test_discovery_failure_leaves_the_passthrough_list_authoritative(self):
        """Missing AstrBot internals must not route every message to Meguri."""

        with patch(
            "adapters.astrbot.astrbot_plugin_meguri_gateway.bridge."
            "registered_plugin_commands",
            return_value=frozenset({"抽老婆"}),
        ):
            policy = MessageRoutePolicy(route_all_private_messages=True)
            self.assertTrue(policy.matches_passthrough("抽老婆"))
            self.assertFalse(policy.matches_passthrough("随便聊聊"))
