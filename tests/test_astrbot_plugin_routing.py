import importlib
import os
import sys
import time
import types
import unittest
from unittest.mock import patch

from adapters.astrbot.astrbot_plugin_meguri_gateway import bridge
from adapters.astrbot.astrbot_plugin_meguri_gateway.bridge import MessageRoutePolicy
from adapters.astrbot.astrbot_plugin_meguri_gateway.plugin_routing import plugins_own_event
from tests.test_astrbot_plugin_entrypoint import (
    MODULE_NAME, _Event, _Gateway, _astrbot_modules,
)


def handler(plugin="other_plugin", name="command", filters=("CommandFilter",), instance=None):
    return types.SimpleNamespace(
        handler_module_path=f"data.plugins.{plugin}.main",
        handler_name=name,
        event_filters=[type(kind, (), {})() for kind in filters],
        handler=types.SimpleNamespace(__self__=instance),
    )


class Event(_Event):
    def __init__(self, text, handlers=None, *, normalized=False, cards=()):
        super().__init__()
        self.text = text
        self.is_at_or_wake_command = normalized
        self.message_obj = types.SimpleNamespace(
            message_id="routing-test", timestamp=123, message=list(cards),
        )
        if handlers is not None:
            self.extras["activated_handlers"] = handlers

    def get_message_str(self):
        return self.text

    def get_extra(self, key):
        return self.extras.get(key)


class PluginRoutingEntrypointTests(unittest.IsolatedAsyncioTestCase):
    async def run_event(self, event, config=None):
        sys.modules.pop(MODULE_NAME, None)
        with patch.dict(sys.modules, _astrbot_modules()), patch.dict(
            os.environ, {"MEGURI_IDENTITY_SALT": "routing-test-salt"},
        ):
            module = importlib.import_module(MODULE_NAME)
            plugin = module.MeguriGatewayPlugin(
                object(), {"route_all_private_messages": True, **(config or {})},
            )
            await plugin.gateway.close()
            plugin.gateway = gateway = _Gateway()
            results = [result async for result in plugin.on_message(event)]
            await plugin.terminate()
        sys.modules.pop(MODULE_NAME, None)
        return results, gateway.messages

    async def test_matched_commands_groups_and_regex_yield_without_core_or_stop(self):
        for kind, text in (
            ("CommandFilter", "B站搜索 猫"),
            ("CommandGroupFilter", "gal 作品 星之梦"),
            ("RegexFilter", "看看 https://b23.tv/example"),
        ):
            with self.subTest(kind=kind):
                event = Event(text, [handler(filters=(kind,))])
                parsed = {"other.command": {"query": "猫"}}
                event.extras["handlers_parsed_params"] = parsed
                results, messages = await self.run_event(event)
                self.assertEqual((results, messages), ([], []))
                self.assertTrue(event.llm_blocked)
                self.assertFalse(event.stopped)
                self.assertNotIn("meguri_render_payload", event.extras)
                self.assertIs(event.extras["handlers_parsed_params"], parsed)

    async def test_session_filtered_registry_command_does_not_claim_message(self):
        event = Event("群分析", [])
        with patch(
            "adapters.astrbot.astrbot_plugin_meguri_gateway.bridge.registered_plugin_commands",
            return_value=frozenset({"群分析"}),
        ) as discovery:
            _, messages = await self.run_event(event)
        discovery.assert_not_called()
        self.assertEqual(len(messages), 1)

    async def test_explicit_meguri_after_wake_normalization_keeps_core_ownership(self):
        for text, normalized in (
            ("/meguri chat BV1xx411c7mD", False),
            ("meguri chat BV1xx411c7mD", True),
        ):
            event = Event(text, [handler(filters=("RegexFilter",))], normalized=normalized)
            if normalized:
                event.message_obj.message_str = "/" + text
            _, messages = await self.run_event(event)
            self.assertEqual(messages[0].text, "/meguri chat BV1xx411c7mD")
            self.assertTrue(event.stopped)

    async def test_private_wake_flag_alone_does_not_rewrite_bare_meguri_chat(self):
        event = Event("meguri 今天怎么样", [], normalized=True)
        event.message_obj.message_str = "meguri 今天怎么样"
        _, messages = await self.run_event(event)
        self.assertEqual(messages[0].text, "meguri 今天怎么样")

    async def test_configured_alias_survives_missing_registry_and_host_metadata(self):
        event = Event("/JRLP\t参数")
        with patch(
            "adapters.astrbot.astrbot_plugin_meguri_gateway.bridge._registered_command_filters",
            side_effect=ImportError,
        ):
            results, messages = await self.run_event(event, {"passthrough_commands": ["jrlp"]})
        self.assertEqual((results, messages), ([], []))
        self.assertTrue(event.llm_blocked)
        self.assertFalse(event.stopped)

    async def test_passive_listeners_and_gateway_handler_keep_ordinary_core_chat(self):
        event = Event("今天过得怎么样", [
            handler(filters=("EventMessageTypeFilter",)),
            handler(plugin="astrbot_plugin_meguri_gateway"),
        ])
        _, messages = await self.run_event(event)
        self.assertEqual(len(messages), 1)

    async def test_bilibili_card_without_text_still_yields_without_default_llm(self):
        instance = types.SimpleNamespace(enable_parse_miniapp=True)
        card = types.SimpleNamespace(type="Json", data={"meta": {"news": {"tag": "哔哩哔哩", "jumpUrl": "https://b23.tv/example"}}})
        event = Event("", [handler("astrbot_plugin_bilibili", "parse_miniapp", (), instance)], cards=[card])
        results, messages = await self.run_event(event)
        self.assertEqual((results, messages), ([], []))
        self.assertTrue(event.llm_blocked)
        self.assertFalse(event.stopped)


class AutomaticTriggerTests(unittest.TestCase):
    def test_legacy_discovery_includes_independent_command_groups_and_aliases(self):
        class CommandFilter:
            def get_complete_command_names(self):
                return ["B站搜索", "搜视频"]

        class CommandGroupFilter:
            def get_complete_command_names(self):
                return ["旮旯", "gal"]

        command_module = types.ModuleType("astrbot.core.star.filter.command")
        command_module.CommandFilter = CommandFilter
        group_module = types.ModuleType("astrbot.core.star.filter.command_group")
        group_module.CommandGroupFilter = CommandGroupFilter
        registry_module = types.ModuleType("astrbot.core.star.star_handler")
        registry_module.EventType = types.SimpleNamespace(AdapterMessageEvent="message")
        registry_module.star_handlers_registry = types.SimpleNamespace(
            get_handlers_by_event_type=lambda _: [
                types.SimpleNamespace(handler_module_path="other", event_filters=[CommandFilter(), CommandGroupFilter()]),
                types.SimpleNamespace(handler_module_path="astrbot_plugin_meguri_gateway", event_filters=[CommandFilter()]),
            ],
        )
        with patch.dict(sys.modules, {
            command_module.__name__: command_module,
            group_module.__name__: group_module,
            registry_module.__name__: registry_module,
        }), patch.object(bridge, "_PLUGIN_COMMAND_CACHE", None):
            self.assertEqual(bridge.registered_plugin_commands(), frozenset({"b站搜索", "搜视频", "旮旯", "gal"}))

    def test_media_links_require_parser_configuration_whitelist_and_actual_link(self):
        instance = types.SimpleNamespace(
            whitelist={"enable": False},
            _should_parse=lambda text: text.startswith("解析 "),
            parser_manager=types.SimpleNamespace(extract_all_links=lambda text: [text] if "b23.tv/" in text else []),
        )
        handlers = [handler("astrbot_plugin_media_parser", "auto_parse", (), instance)]
        self.assertTrue(plugins_own_event(Event("解析 https://b23.tv/example"), handlers))
        self.assertFalse(plugins_own_event(Event("https://b23.tv/example"), handlers))
        self.assertFalse(plugins_own_event(Event("解析一下我今天的心情"), handlers))
        instance.whitelist = {"enable": True, "user": ["another-user"], "group": []}
        self.assertFalse(plugins_own_event(Event("解析 https://b23.tv/example"), handlers))

    def test_bilibili_cards_match_structured_metadata_and_enabled_parse_setting(self):
        instance = types.SimpleNamespace(enable_parse_miniapp=True)
        handlers = [handler("astrbot_plugin_bilibili", "parse_miniapp", (), instance)]
        card = types.SimpleNamespace(type="Json", data={"meta": {"detail_1": {"title": "哔哩哔哩", "qqdocurl": "https://b23.tv/example"}}})
        self.assertTrue(plugins_own_event(Event("分享卡片", cards=[card]), handlers))
        instance.enable_parse_miniapp = False
        self.assertFalse(plugins_own_event(Event("分享卡片", cards=[card]), handlers))
        instance.enable_parse_miniapp = True
        card.data = "malformed json"
        self.assertFalse(plugins_own_event(Event("分享卡片", cards=[card]), handlers))

    def test_image_search_owns_configured_triggers_and_unexpired_followups(self):
        instance = types.SimpleNamespace(
            trigger_keywords=["以图搜图", "/搜图"], user_states={},
            text_confirm_timeout=10, search_params_timeout=30,
        )
        handlers = [handler("astrbot_plugin_img_rev_searcher", "on_message", (), instance)]
        self.assertTrue(plugins_own_event(Event("以图搜图"), handlers))
        raw = types.SimpleNamespace(text="/搜图")
        self.assertTrue(plugins_own_event(Event("搜图", cards=[raw]), handlers))
        self.assertFalse(plugins_own_event(Event("随便聊聊"), handlers))
        instance.user_states["user-1"] = {"step": "waiting_both", "timestamp": time.time()}
        self.assertTrue(plugins_own_event(Event("saucenao"), handlers))
        instance.user_states["user-1"]["timestamp"] -= 31
        self.assertFalse(plugins_own_event(Event("随便聊聊"), handlers))

    def test_meme_templates_use_loaded_keywords_and_plugin_enablement(self):
        manager = types.SimpleNamespace(
            _meme_keywords=["摸"],
            normalize_trigger_message=lambda text, prefix: text[len(prefix):] if text.startswith(prefix) else "",
        )
        config = types.SimpleNamespace(is_plugin_enabled=lambda: True, trigger_prefix="!")
        instance = types.SimpleNamespace(meme_config=config, meme_manager=types.SimpleNamespace(template_manager=manager))
        handlers = [handler("astrbot_plugin_meme_generator", "generate_meme", (), instance)]
        self.assertTrue(plugins_own_event(Event("!摸 @某人"), handlers))
        self.assertFalse(plugins_own_event(Event("摸摸头"), handlers))
        config.is_plugin_enabled = lambda: False
        self.assertFalse(plugins_own_event(Event("!摸"), handlers))

    def test_inspection_failure_does_not_break_unrelated_chat(self):
        handlers = [handler("astrbot_plugin_media_parser", "auto_parse", (), object())]
        self.assertFalse(plugins_own_event(Event("聊天"), handlers))

    def test_aliases_are_case_insensitive_slash_aware_and_whitespace_bounded(self):
        policy = MessageRoutePolicy(passthrough_commands=("jrlp", "查老婆"))
        for text in ("/JRLP", "查老婆\n@某人", "jrlp\t参数"):
            self.assertTrue(policy.matches_passthrough(text, include_registered=False))
        for text in ("jrlpx", "/meguri chat jrlp", "查老婆婆"):
            self.assertFalse(policy.matches_passthrough(text, include_registered=False))
