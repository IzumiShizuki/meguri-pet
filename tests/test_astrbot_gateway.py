import unittest
from datetime import datetime, timezone

import httpx

from adapters.astrbot.astrbot_plugin_meguri_gateway.client import (
    CoreProtocolError,
    HttpMeguriCoreClient,
)
from adapters.astrbot.astrbot_plugin_meguri_gateway.commands import parse_command
from adapters.astrbot.astrbot_plugin_meguri_gateway.gateway import MeguriGateway
from adapters.astrbot.astrbot_plugin_meguri_gateway.identity import IdentityBindingStore
from adapters.astrbot.astrbot_plugin_meguri_gateway.models import PlatformMessage


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


class FakeCoreClient:
    def __init__(self, fail=False):
        self.fail = fail
        self.respond_payloads = []
        self.overrides = []
        self.cleared = []

    async def respond(self, payload):
        if self.fail:
            raise CoreProtocolError("offline")
        self.respond_payloads.append(payload)
        return {
            "turn_id": "turn-test",
            "response": {
                "reply": "Meguri reply",
                "expression_tag": "neutral",
                "expression_intensity": "low",
                "voice_style": "neutral",
                "memory_candidates": [],
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


class CommandParsingTests(unittest.TestCase):
    def test_override_duration_is_timezone_aware(self):
        now = datetime(2026, 7, 13, tzinfo=timezone.utc)
        command = parse_command("/meguri mode private 2h", now=now)
        self.assertEqual(command.action, "set_override")
        self.assertEqual(command.override["mode"], "private")
        self.assertEqual(command.override["expires_at"], "2026-07-13T02:00:00+00:00")

    def test_disabled_outfit_falls_back_to_help(self):
        self.assertEqual(parse_command("/meguri outfit 07").action, "help")


class GatewayTests(unittest.IsolatedAsyncioTestCase):
    async def test_gateway_declares_astrbot_capability_boundary(self):
        core = FakeCoreClient()
        reply = await MeguriGateway(core, IdentityBindingStore("test-salt")).handle(
            platform_message()
        )
        self.assertEqual(reply.text, "Meguri reply")
        capabilities = core.respond_payloads[0]["client_capabilities"]
        self.assertFalse(capabilities["voice"])
        self.assertFalse(capabilities["screen_context"])
        self.assertEqual(core.respond_payloads[0]["client_id"], "astrbot")

    async def test_commands_do_not_enter_chat_pipeline(self):
        core = FakeCoreClient()
        gateway = MeguriGateway(core, IdentityBindingStore("test-salt"))
        updated = await gateway.handle(platform_message(text="/meguri relation lover 2h"))
        status = await gateway.handle(platform_message(text="/meguri status"))
        reset = await gateway.handle(platform_message(text="/meguri reset"))
        self.assertTrue(updated.command_handled)
        self.assertIn("mode=work", status.text)
        self.assertTrue(reset.command_handled)
        self.assertEqual(core.respond_payloads, [])
        self.assertEqual(len(core.overrides), 1)
        self.assertEqual(len(core.cleared), 1)

    async def test_core_failure_returns_fast_degraded_reply(self):
        reply = await MeguriGateway(
            FakeCoreClient(fail=True),
            IdentityBindingStore("test-salt"),
        ).handle(platform_message())
        self.assertTrue(reply.degraded)
        self.assertIn("暂时不可用", reply.text)


class HttpCoreClientTests(unittest.IsolatedAsyncioTestCase):
    async def test_http_client_rejects_wildcard_and_public_urls(self):
        with self.assertRaises(ValueError):
            HttpMeguriCoreClient("http://0.0.0.0:8100")
        with self.assertRaises(ValueError):
            HttpMeguriCoreClient("http://111.228.35.186:8100")

    async def test_http_client_validates_object_response(self):
        async def handler(request):
            return httpx.Response(200, json=["not-an-object"])

        client = HttpMeguriCoreClient(transport=httpx.MockTransport(handler))
        with self.assertRaises(CoreProtocolError):
            await client.respond({"message": "test"})
