import importlib
import os
import sys
import types
import unittest
from unittest.mock import patch

from adapters.astrbot.astrbot_plugin_meguri_gateway.models import (
    GatewayReply,
    PlatformMessage,
)


MODULE_NAME = "adapters.astrbot.astrbot_plugin_meguri_gateway.main"


class _Filter:
    EventMessageType = types.SimpleNamespace(ALL="all")

    @staticmethod
    def event_message_type(*_args, **_kwargs):
        return lambda function: function


class _Star:
    def __init__(self, context):
        self.context = context


class _Logger:
    @staticmethod
    def info(_message):
        return None


class _Gateway:
    def __init__(self):
        self.messages = []
        self.closed = False

    async def handle(self, message):
        self.messages.append(message)
        return GatewayReply(
            text="Meguri plugin reply",
            metadata={"meguri_render_payload": {"schema_version": 1}},
        )

    async def close(self):
        self.closed = True


class _Event:
    class Message:
        message_id = "entrypoint-message-1"
        timestamp = 123

    message_obj = Message()
    unified_msg_origin = "QQOfficial:FriendMessage:user-1"

    def __init__(self):
        self.llm_blocked = False
        self.stopped = False
        self.stopped_when_reply_was_built = None
        self.extras = {}

    def get_platform_name(self):
        return "QQOfficial"

    def get_self_id(self):
        return "bot-account"

    def get_sender_id(self):
        return "user-1"

    def is_private_chat(self):
        return True

    def get_session_id(self):
        return self.unified_msg_origin

    def get_group_id(self):
        return None

    def get_message_str(self):
        return "/meguri status"

    def is_admin(self):
        return True

    def should_call_llm(self, blocked):
        self.llm_blocked = blocked

    def stop_event(self):
        self.stopped = True

    def plain_result(self, text):
        self.stopped_when_reply_was_built = self.stopped
        return text

    def set_extra(self, key, value):
        self.extras[key] = value


def _astrbot_modules():
    astrbot = types.ModuleType("astrbot")
    api = types.ModuleType("astrbot.api")
    event = types.ModuleType("astrbot.api.event")
    star = types.ModuleType("astrbot.api.star")
    api.AstrBotConfig = dict
    api.logger = _Logger()
    event.AstrMessageEvent = object
    event.filter = _Filter()
    star.Context = object
    star.Star = _Star
    star.register = lambda *_args, **_kwargs: lambda plugin: plugin
    astrbot.api = api
    return {
        "astrbot": astrbot,
        "astrbot.api": api,
        "astrbot.api.event": event,
        "astrbot.api.star": star,
    }


class AstrBotPluginEntrypointTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_entrypoint_stops_default_llm_and_returns_gateway_reply(self):
        sys.modules.pop(MODULE_NAME, None)
        with patch.dict(sys.modules, _astrbot_modules()), patch.dict(
            os.environ,
            {"MEGURI_IDENTITY_SALT": "entrypoint-test-salt"},
        ):
            module = importlib.import_module(MODULE_NAME)
            plugin = module.MeguriGatewayPlugin(object(), {})
            gateway = _Gateway()
            await plugin.gateway.close()
            plugin.gateway = gateway
            event = _Event()
            results = [result async for result in plugin.on_message(event)]
            self.assertEqual(results, ["Meguri plugin reply"])
            self.assertTrue(event.llm_blocked)
            self.assertTrue(event.stopped)
            self.assertFalse(event.stopped_when_reply_was_built)
            self.assertEqual(
                event.extras["meguri_render_payload"], {"schema_version": 1}
            )
            self.assertEqual(gateway.messages[0].message_id, "entrypoint-message-1")
            await plugin.terminate()
            self.assertTrue(gateway.closed)
        sys.modules.pop(MODULE_NAME, None)

    async def test_remote_authority_requires_exact_namespaced_sender(self):
        sys.modules.pop(MODULE_NAME, None)
        sender_key = "QQOfficial:bot-account:user-1"
        with patch.dict(sys.modules, _astrbot_modules()), patch.dict(
            os.environ,
            {"MEGURI_IDENTITY_SALT": "entrypoint-test-salt"},
        ):
            module = importlib.import_module(MODULE_NAME)
            plugin = module.MeguriGatewayPlugin(
                object(),
                {"remote_operator_senders": [sender_key]},
            )
            base = {
                "platform": "QQOfficial",
                "account_id": "bot-account",
                "sender_id": "user-1",
                "conversation_id": "user-1",
                "message_id": "message-1",
                "text": "/meguri devices",
                "chat_type": "private",
            }
            exact = PlatformMessage(**base)
            collision = PlatformMessage(
                **{**base, "platform": "Telegram", "sender_is_admin": True}
            )
            admin_only = PlatformMessage(
                **{**base, "sender_id": "another-user", "sender_is_admin": True}
            )
            self.assertTrue(plugin.gateway.remote_authorizer(exact))
            self.assertFalse(plugin.gateway.remote_authorizer(collision))
            self.assertFalse(plugin.gateway.remote_authorizer(admin_only))
            await plugin.terminate()
        sys.modules.pop(MODULE_NAME, None)
