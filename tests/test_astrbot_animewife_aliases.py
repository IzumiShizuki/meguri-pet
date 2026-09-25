"""Regression coverage for the local animewife command aliases.

The upstream plugin only listens to group messages and only knows the Chinese
command names, so a private-chat `jrlp` fell through to the LLM. These tests pin
the two behaviours the live deployment depends on:

* `jrlp` (and friends) resolve to the wife plugin instead of plain chat, and
* private chats get their own state scope instead of crashing on `group_id`.
"""

import enum
import importlib
import sys
import types
import unittest
from unittest.mock import patch


MODULE_NAME = "adapters.astrbot.astrbot_plugin_animewifex.main"


class _Logger:
    def __getattr__(self, _name):
        return lambda *_args, **_kwargs: None


class _Flag(enum.Flag):
    """Mirror of AstrBot's ``EventMessageType`` flag set."""

    GROUP_MESSAGE = enum.auto()
    PRIVATE_MESSAGE = enum.auto()
    OTHER_MESSAGE = enum.auto()
    ALL = GROUP_MESSAGE | PRIVATE_MESSAGE | OTHER_MESSAGE


class _Filter:
    EventMessageType = _Flag

    def __getattr__(self, _name):
        return lambda *_args, **_kwargs: lambda function: function


class _Star:
    def __init__(self, context=None):
        self.context = context


class _StarTools:
    @staticmethod
    def get_data_dir(_plugin_id):
        return None


class _Component:
    def __init__(self, value=""):
        self.value = value


class _Event:
    """Minimal stand-in for AstrMessageEvent."""

    def __init__(self, text, sender_id="user-1", group_id=None):
        self.message_str = text
        self.is_at_or_wake_command = False
        self.call_llm = False
        self.message_obj = types.SimpleNamespace(
            group_id=group_id, message=[], message_id="m-1", timestamp=1
        )
        self._sender_id = sender_id

    def should_call_llm(self, call_llm):
        self.call_llm = call_llm

    def get_sender_id(self):
        return self._sender_id

    def get_self_id(self):
        return "bot-account"

    def get_sender_name(self):
        return "tester"

    def is_private_chat(self):
        return self.message_obj.group_id is None


def _astrbot_modules():
    aiohttp = types.ModuleType("aiohttp")
    aiohttp.ClientSession = type("ClientSession", (), {})
    astrbot = types.ModuleType("astrbot")
    api = types.ModuleType("astrbot.api")
    components = types.ModuleType("astrbot.api.message_components")
    event = types.ModuleType("astrbot.api.event")
    event_filter = types.ModuleType("astrbot.api.event.filter")
    star = types.ModuleType("astrbot.api.star")

    api.logger = _Logger()
    api.AstrBotConfig = dict
    components.At = _Component
    components.Plain = _Component
    components.Image = _Component
    event.AstrMessageEvent = _Event
    event.filter = _Filter()
    event_filter.EventMessageType = _Filter.EventMessageType
    star.Context = object
    star.Star = _Star
    star.StarTools = _StarTools

    api.message_components = components
    astrbot.api = api
    return {
        "aiohttp": aiohttp,
        "astrbot": astrbot,
        "astrbot.api": api,
        "astrbot.api.message_components": components,
        "astrbot.api.event": event,
        "astrbot.api.event.filter": event_filter,
        "astrbot.api.star": star,
    }


def _plugin_config():
    return {
        "admins": [],
        "need_prefix": False,
        "image_base_url": "https://example.invalid/wife",
        "image_list_url": "https://example.invalid/list.txt",
        "imouto_only": False,
    }


class _Context:
    """Minimal Star context exposing AstrBot's global config."""

    @staticmethod
    def get_config():
        return {"timezone": "Asia/Shanghai"}


class AnimewifeAliasTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        sys.modules.pop(MODULE_NAME, None)
        self.module_patch = patch.dict(sys.modules, _astrbot_modules())
        self.module_patch.start()
        self.addCleanup(self.module_patch.stop)
        self.module = importlib.import_module(MODULE_NAME)

    def tearDown(self):
        sys.modules.pop(MODULE_NAME, None)

    def _new_plugin(self):
        plugin = self.module.WifePlugin.__new__(self.module.WifePlugin)
        plugin.context = _Context()
        plugin.config = _plugin_config()
        plugin._init_config()
        plugin._init_commands()
        return plugin

    def test_alias_table_covers_the_pinyin_entrypoints(self):
        plugin = self._new_plugin()

        for alias in ("jrlp", "jrlb", "clp", "今日老婆"):
            self.assertIn(alias, plugin.commands, alias)

        # Bound methods compare by identity, so compare the underlying functions.
        self.assertIs(plugin.commands["jrlp"].__func__, plugin.today_wife.__func__)
        self.assertIs(plugin.commands["clp"].__func__, plugin.animewife.__func__)

    def test_longer_command_is_matched_before_its_prefix(self):
        plugin = self._new_plugin()

        # `_command_names` is length-sorted, so `jrlp帮助` is tried before `jrlp`
        # and a help request is not swallowed by the draw alias.
        self.assertLess(
            plugin._command_names.index("jrlp帮助"),
            plugin._command_names.index("jrlp"),
        )

    async def test_private_chat_gets_its_own_scope_instead_of_group_id(self):
        plugin = self._new_plugin()

        self.assertEqual(
            plugin._session_key(_Event("jrlp", group_id=None)), "private_user-1"
        )
        self.assertEqual(plugin._session_key(_Event("jrlp", group_id=12345)), "12345")

    async def test_jrlp_draws_when_today_has_no_wife_yet(self):
        plugin = self._new_plugin()
        calls = []

        async def draw(event):
            calls.append("draw")
            yield "drawn"

        async def search(event):
            calls.append("search")
            yield "searched"

        plugin.animewife = draw
        plugin.search_wife = search
        plugin.load_group_config = lambda _gid: {}

        results = [item async for item in plugin.today_wife(_Event("jrlp"))]

        self.assertEqual(calls, ["draw"])
        self.assertEqual(results, ["drawn"])

    async def test_jrlp_only_reads_when_today_already_has_a_wife(self):
        plugin = self._new_plugin()
        calls = []
        today = self.module.get_today(plugin.tz)

        async def draw(event):
            calls.append("draw")
            yield "drawn"

        async def search(event):
            calls.append("search")
            yield "searched"

        plugin.animewife = draw
        plugin.search_wife = search
        plugin.load_group_config = lambda _gid: {
            "user-1": {"img": "a.png", "date": today, "owner": "tester"}
        }

        results = [item async for item in plugin.today_wife(_Event("jrlp"))]

        self.assertEqual(calls, ["search"])
        self.assertEqual(results, ["searched"])

    async def test_dispatch_is_case_insensitive_for_ascii_aliases(self):
        plugin = self._new_plugin()
        seen = []

        async def handler(event):
            seen.append(event.message_str)
            yield "ok"

        plugin.commands = dict(plugin.commands)
        plugin.commands["jrlp"] = handler
        plugin._command_names = list(plugin.commands)
        event = _Event("JRLP")

        results = [item async for item in plugin.on_all_messages(event)]

        self.assertEqual(seen, ["JRLP"])
        self.assertEqual(results, ["ok"])
        # The plugin owns the message, so the default LLM must stay suppressed.
        self.assertTrue(event.call_llm)

    async def test_dispatch_ignores_unrelated_chat(self):
        plugin = self._new_plugin()
        event = _Event("今天天气怎么样")

        results = [item async for item in plugin.on_all_messages(event)]

        self.assertEqual(results, [])
        # A message this plugin does not own must keep the normal LLM path.
        self.assertFalse(event.call_llm)
