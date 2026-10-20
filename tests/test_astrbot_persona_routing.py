import importlib
import json
import os
import sys
import tempfile
import types
import unittest
from contextlib import asynccontextmanager
from pathlib import Path
from unittest.mock import AsyncMock, patch
from zipfile import ZipFile

from adapters.astrbot.astrbot_plugin_meguri_gateway.persona_routing import (
    PersonaBackendRouting,
)
from adapters.astrbot.package_plugin import PLUGIN_NAME, build_plugin_archive
from tests.test_astrbot_plugin_entrypoint import (
    MODULE_NAME,
    _Event,
    _Gateway,
    _astrbot_modules,
)


def context_for(
    selected_id="爱莉", conversation_id="conversation-1", persona_id="爱莉"
):
    return types.SimpleNamespace(
        conversation_manager=types.SimpleNamespace(
            get_curr_conversation_id=AsyncMock(return_value=conversation_id),
            get_conversation=AsyncMock(
                return_value=types.SimpleNamespace(persona_id=persona_id)
            ),
        ),
        persona_manager=types.SimpleNamespace(
            resolve_selected_persona=AsyncMock(
                return_value=(selected_id, None, None, False)
            ),
        ),
    )


class PersonaEvent(_Event):
    def __init__(self, text="晚上好", *, group_id=None, sender_id="user-1"):
        super().__init__()
        self.text = text
        self.group_id = group_id
        self.sender_id = sender_id
        kind = "GroupMessage" if group_id else "FriendMessage"
        self.unified_msg_origin = f"QQOfficial:{kind}:{group_id or sender_id}"
        self.llm_calls = []

    def get_message_str(self):
        return self.text

    def get_sender_id(self):
        return self.sender_id

    def is_private_chat(self):
        return self.group_id is None

    def get_group_id(self):
        return self.group_id

    def get_extra(self, key):
        return self.extras.get(key)

    def should_call_llm(self, blocked):
        self.llm_calls.append(blocked)
        super().should_call_llm(blocked)


class PersonaPolicyTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.policy = PersonaBackendRouting(True, frozenset({"爱莉", "Meguri"}))

    async def test_disabled_policy_needs_no_astrbot_api(self):
        self.assertTrue(
            await PersonaBackendRouting().should_use_meguri(object(), object())
        )

    async def test_empty_list_leaves_native_chat_without_lookup(self):
        self.assertFalse(
            await PersonaBackendRouting(True).should_use_meguri(object(), object())
        )

    async def test_uses_selected_conversation_and_native_resolver(self):
        context = context_for()
        event = PersonaEvent()
        self.assertTrue(await self.policy.should_use_meguri(context, event))
        context.conversation_manager.get_curr_conversation_id.assert_awaited_once_with(
            event.unified_msg_origin
        )
        context.conversation_manager.get_conversation.assert_awaited_once_with(
            event.unified_msg_origin, "conversation-1"
        )
        context.persona_manager.resolve_selected_persona.assert_awaited_once_with(
            umo=event.unified_msg_origin,
            conversation_persona_id="爱莉",
            platform_name="QQOfficial",
        )

    async def test_matching_is_exact_and_case_sensitive(self):
        for persona in ("助手", "meguri", "Meguri Plus", "", None, 42):
            with self.subTest(persona=persona):
                self.assertFalse(
                    await self.policy.should_use_meguri(
                        context_for(persona), PersonaEvent()
                    )
                )

    async def test_no_persona_sentinel_is_native_even_if_listed(self):
        policy = PersonaBackendRouting(True, frozenset({"[%None]"}))
        self.assertFalse(
            await policy.should_use_meguri(context_for("[%None]"), PersonaEvent())
        )

    async def test_no_conversation_delegates_default_without_creating_one(self):
        context = context_for(conversation_id=None)
        self.assertTrue(await self.policy.should_use_meguri(context, PersonaEvent()))
        context.conversation_manager.get_conversation.assert_not_awaited()
        self.assertIsNone(
            context.persona_manager.resolve_selected_persona.call_args.kwargs[
                "conversation_persona_id"
            ]
        )

    async def test_resolved_override_takes_precedence_over_stored_persona(self):
        for selected, stored, expected in (
            ("助手", "爱莉", False),
            ("爱莉", "助手", True),
        ):
            with self.subTest(selected=selected):
                self.assertEqual(
                    await self.policy.should_use_meguri(
                        context_for(selected, persona_id=stored), PersonaEvent()
                    ),
                    expected,
                )

    async def test_missing_conversation_does_not_guess_its_persona(self):
        context = context_for()
        context.conversation_manager.get_conversation.return_value = None
        self.assertFalse(await self.policy.should_use_meguri(context, PersonaEvent()))
        context.persona_manager.resolve_selected_persona.assert_not_awaited()

    async def test_lookup_failure_does_not_log_exception_text(self):
        for attribute, method in (
            ("conversation_manager", "get_curr_conversation_id"),
            ("conversation_manager", "get_conversation"),
            ("persona_manager", "resolve_selected_persona"),
        ):
            with self.subTest(method=method):
                context = context_for()
                getattr(getattr(context, attribute), method).side_effect = RuntimeError(
                    "secret-token-and-private-message"
                )
                with self.assertLogs(
                    "adapters.astrbot.astrbot_plugin_meguri_gateway.persona_routing",
                    level="WARNING",
                ) as logs:
                    self.assertFalse(
                        await self.policy.should_use_meguri(context, PersonaEvent())
                    )
                self.assertNotIn("secret-token", " ".join(logs.output))

    async def test_unsupported_host_uses_native_pipeline(self):
        with self.assertLogs(level="WARNING"):
            self.assertFalse(
                await self.policy.should_use_meguri(object(), PersonaEvent())
            )

    async def test_missing_origin_does_not_resolve_another_sessions_persona(self):
        event = PersonaEvent()
        for origin in (None, "", "  "):
            event.unified_msg_origin = origin
            self.assertFalse(await self.policy.should_use_meguri(object(), event))


@asynccontextmanager
async def gateway_plugin(context, **config):
    sys.modules.pop(MODULE_NAME, None)
    with (
        patch.dict(sys.modules, _astrbot_modules()),
        patch.dict(os.environ, {"MEGURI_IDENTITY_SALT": "persona-routing-test-salt"}),
    ):
        module = importlib.import_module(MODULE_NAME)
        plugin = module.MeguriGatewayPlugin(
            context,
            {
                "route_all_private_messages": True,
                "persona_routing_enabled": True,
                "meguri_persona_ids": ["爱莉"],
                **config,
            },
        )
        await plugin.gateway.close()
        plugin.gateway = _Gateway()
        try:
            yield plugin
        finally:
            await plugin.terminate()
    sys.modules.pop(MODULE_NAME, None)


class PersonaEntrypointTests(unittest.IsolatedAsyncioTestCase):
    async def assert_native(self, plugin, event):
        before = dict(event.extras)
        replies = [reply async for reply in plugin.on_message(event)]
        self.assertEqual(replies, [])
        self.assertEqual(plugin.gateway.messages, [])
        self.assertEqual(event.llm_calls, [])
        self.assertFalse(event.stopped)
        self.assertEqual(event.extras, before)

    async def test_selected_persona_owns_one_core_turn(self):
        async with gateway_plugin(context_for()) as plugin:
            event = PersonaEvent()
            replies = [reply async for reply in plugin.on_message(event)]
            self.assertEqual(replies, ["Meguri plugin reply"])
            self.assertEqual(len(plugin.gateway.messages), 1)
            self.assertEqual(event.llm_calls, [True])
            self.assertTrue(event.stopped)
            self.assertIn("meguri_render_payload", event.extras)

    async def test_native_persona_keeps_event_and_existing_suppression_untouched(self):
        async with gateway_plugin(context_for("助手")) as plugin:
            event = PersonaEvent()
            event.llm_blocked = True
            await self.assert_native(plugin, event)
            self.assertTrue(event.llm_blocked)

    async def test_native_persona_does_not_create_natural_language_todo(self):
        async with gateway_plugin(context_for("助手")) as plugin:
            plugin.timeprism_todo_creation = types.SimpleNamespace(handle=AsyncMock())
            await self.assert_native(plugin, PersonaEvent("提醒我明天下午3点开会"))
            plugin.timeprism_todo_creation.handle.assert_not_awaited()

    async def test_explicit_command_bypasses_persona_lookup(self):
        async with gateway_plugin(object()) as plugin:
            event = PersonaEvent("/meguri status")
            replies = [reply async for reply in plugin.on_message(event)]
            self.assertEqual(replies, ["Meguri plugin reply"])
            self.assertEqual(len(plugin.gateway.messages), 1)

    async def test_explicit_command_still_denies_unauthorized_sender(self):
        async with gateway_plugin(
            object(), allowed_senders=["QQOfficial:bot-account:other"]
        ) as plugin:
            event = PersonaEvent("/meguri status")
            replies = [reply async for reply in plugin.on_message(event)]
            self.assertEqual(replies, ["当前聊天账号未获准访问 Meguri。"])
            self.assertEqual(plugin.gateway.messages, [])
            self.assertTrue(event.stopped)

    async def test_explicit_group_command_still_requires_group_permission(self):
        async with gateway_plugin(object()) as plugin:
            await self.assert_native(
                plugin, PersonaEvent("/meguri status", group_id="group-1")
            )

    async def test_legacy_switch_and_absent_configuration_keep_existing_routing(self):
        async with gateway_plugin(object(), persona_routing_enabled=False) as plugin:
            replies = [reply async for reply in plugin.on_message(PersonaEvent())]
            self.assertEqual(replies, ["Meguri plugin reply"])

    async def test_enabled_empty_allowlist_leaves_native_chat(self):
        async with gateway_plugin(object(), meguri_persona_ids=[]) as plugin:
            await self.assert_native(plugin, PersonaEvent())

    async def test_lookup_failure_keeps_native_entrypoint_without_error_reply(self):
        async with gateway_plugin(object()) as plugin:
            with self.assertLogs(level="WARNING"):
                await self.assert_native(plugin, PersonaEvent())

    async def test_selected_persona_does_not_override_existing_automatic_or_sender_rules(
        self,
    ):
        for config, event in (
            ({"route_all_private_messages": False}, PersonaEvent()),
            ({"allowed_senders": ["QQOfficial:bot-account:other"]}, PersonaEvent()),
            ({"route_all_group_messages": True}, PersonaEvent(group_id="group-1")),
            ({"allow_group_messages": True}, PersonaEvent(group_id="group-1")),
        ):
            with self.subTest(config=config):
                async with gateway_plugin(context_for(), **config) as plugin:
                    await self.assert_native(plugin, event)

    async def test_plugin_command_takes_priority_without_persona_lookup(self):
        async with gateway_plugin(object(), passthrough_commands=["jrlp"]) as plugin:
            event = PersonaEvent("jrlp")
            replies = [reply async for reply in plugin.on_message(event)]
            self.assertEqual(replies, [])
            self.assertEqual(plugin.gateway.messages, [])
            self.assertFalse(event.stopped)

    async def test_persona_switch_applies_to_next_message_without_restart(self):
        context = context_for("助手")
        async with gateway_plugin(context) as plugin:
            await self.assert_native(plugin, PersonaEvent())
            context.persona_manager.resolve_selected_persona.return_value = (
                "爱莉",
                None,
                None,
                False,
            )
            replies = [reply async for reply in plugin.on_message(PersonaEvent())]
            self.assertEqual(replies, ["Meguri plugin reply"])
            context.persona_manager.resolve_selected_persona.return_value = (
                "助手",
                None,
                None,
                False,
            )
            plugin.gateway.messages.clear()
            await self.assert_native(plugin, PersonaEvent())
            self.assertEqual(
                context.conversation_manager.get_curr_conversation_id.await_count, 3
            )

    async def test_private_and_two_groups_use_their_own_effective_persona(self):
        context = context_for()
        context.conversation_manager.get_curr_conversation_id.side_effect = (
            lambda origin: origin
        )

        async def resolve(*, umo, **_kwargs):
            selected = "爱莉" if umo.endswith(":group-1") else "助手"
            return selected, None, None, False

        context.persona_manager.resolve_selected_persona.side_effect = resolve
        async with gateway_plugin(
            context, allow_group_messages=True, route_all_group_messages=True
        ) as plugin:
            await self.assert_native(plugin, PersonaEvent())
            await self.assert_native(plugin, PersonaEvent(group_id="group-2"))
            event = PersonaEvent(group_id="group-1")
            replies = [reply async for reply in plugin.on_message(event)]
            self.assertEqual(replies, ["Meguri plugin reply"])
            origins = [
                call.kwargs["umo"]
                for call in context.persona_manager.resolve_selected_persona.await_args_list
            ]
            self.assertEqual(len(set(origins)), 3)


class PersonaPackageTests(unittest.TestCase):
    def test_upload_contains_policy_and_safe_config_defaults(self):
        with tempfile.TemporaryDirectory() as directory:
            path = build_plugin_archive(Path(directory) / "gateway.zip")
            with ZipFile(path) as archive:
                self.assertIn(f"{PLUGIN_NAME}/persona_routing.py", archive.namelist())
                schema = json.loads(archive.read(f"{PLUGIN_NAME}/_conf_schema.json"))
        self.assertFalse(schema["persona_routing_enabled"]["default"])
        self.assertEqual(schema["meguri_persona_ids"]["default"], [])
