"""Regression coverage for Meguri decoration isolation in the conversation plugin.

The live server copy (not previously in this repository) renders a Meguri turn from
a high-priority decorator that stops the event afterwards, so other decorators such
as ``meme_manager@99999`` cannot inject components into, or skip, the Meguri output.
These tests pin that behaviour and the non-Meguri path it must not change.
"""

import importlib
import sys
import types
import unittest
from unittest.mock import patch


MODULE_NAME = "adapters.astrbot.astrbot_plugin_chuanhuatong.main"

# Decorator kwargs are recorded so the registered priorities can be asserted.
DECORATOR_CALLS: list[tuple[str, dict]] = []


class _Logger:
    def __getattr__(self, _name):
        return lambda *_args, **_kwargs: None


class _Filter:
    def __init__(self):
        self.EventMessageType = types.SimpleNamespace(ALL="all", GROUP_MESSAGE="group")

    def __getattr__(self, name):
        def decorator(*args, **kwargs):
            DECORATOR_CALLS.append((name, kwargs))
            return lambda function: function

        return decorator


class _Star:
    def __init__(self, context):
        self.context = context


class _StarTools:
    @staticmethod
    def get_data_dir(_plugin_id):
        return None


class _Component:
    def __init__(self, value=""):
        self.value = value


class _ImageComponent(_Component):
    @classmethod
    def fromFileSystem(cls, value):
        return cls(value)


class _MessageChain:
    def __init__(self):
        self.parts = []

    def file_image(self, path):
        self.parts.append(("image", str(path)))
        return self

    def message(self, text):
        self.parts.append(("text", text))
        return self


class _Event:
    def __init__(self, payload=None):
        self._extras = {} if payload is None else {"meguri_render_payload": payload}
        self.stopped = False
        self.sent = []

    def get_extra(self, key, default=None):
        return self._extras.get(key, default)

    def set_extra(self, key, value):
        self._extras[key] = value

    def stop_event(self):
        self.stopped = True

    def is_stopped(self):
        return self.stopped

    async def send(self, chain):
        self.sent.append(list(chain.parts))


def _astrbot_modules():
    aiohttp = types.ModuleType("aiohttp")
    web = types.ModuleType("aiohttp.web")
    astrbot = types.ModuleType("astrbot")
    api = types.ModuleType("astrbot.api")
    components = types.ModuleType("astrbot.api.message_components")
    event = types.ModuleType("astrbot.api.event")
    provider = types.ModuleType("astrbot.api.provider")
    star = types.ModuleType("astrbot.api.star")
    core = types.ModuleType("astrbot.core")
    message = types.ModuleType("astrbot.core.message")
    result = types.ModuleType("astrbot.core.message.message_event_result")

    components.Plain = _Component
    components.Text = _Component
    components.Image = _ImageComponent
    api.AstrBotConfig = dict
    api.logger = _Logger()
    event.AstrMessageEvent = object
    event.filter = _Filter()
    provider.LLMResponse = object
    provider.ProviderRequest = object
    star.Context = object
    star.Star = _Star
    star.StarTools = _StarTools
    star.register = lambda *_args, **_kwargs: lambda plugin: plugin
    result.MessageChain = _MessageChain
    astrbot.api = api
    for name in (
        "Application",
        "AppRunner",
        "FileResponse",
        "HTTPBadRequest",
        "HTTPInternalServerError",
        "HTTPNotFound",
        "HTTPUnauthorized",
        "Request",
        "Response",
        "TCPSite",
    ):
        setattr(web, name, type(name, (), {}))
    web.get = lambda *_args, **_kwargs: None
    web.post = lambda *_args, **_kwargs: None
    web.json_response = lambda *_args, **_kwargs: None
    aiohttp.web = web

    return {
        "aiohttp": aiohttp,
        "aiohttp.web": web,
        "astrbot": astrbot,
        "astrbot.api": api,
        "astrbot.api.message_components": components,
        "astrbot.api.event": event,
        "astrbot.api.provider": provider,
        "astrbot.api.star": star,
        "astrbot.core": core,
        "astrbot.core.message": message,
        "astrbot.core.message.message_event_result": result,
    }


class AstrBotDecorationIsolationTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        DECORATOR_CALLS.clear()
        sys.modules.pop(MODULE_NAME, None)
        self.module_patch = patch.dict(sys.modules, _astrbot_modules())
        self.module_patch.start()
        self.addCleanup(self.module_patch.stop)
        self.module = importlib.import_module(MODULE_NAME)
        self.plugin = self.module.ChuanHuaTongPlugin.__new__(
            self.module.ChuanHuaTongPlugin
        )
        self.rendered = []

        async def render(_event):
            self.rendered.append(True)

        self.plugin._decorate_and_render = render

    def tearDown(self):
        sys.modules.pop(MODULE_NAME, None)

    def test_handlers_are_registered_with_the_isolation_priorities(self):
        registered = sorted(
            kwargs.get("priority")
            for name, kwargs in DECORATOR_CALLS
            if name == "on_decorating_result"
        )

        # The Meguri handler runs first so it beats meme_manager@99999, and the
        # fallback handler runs last so other plugins still decorate their turns.
        self.assertEqual(registered, [-10, 1_000_000])

    async def test_meguri_turn_renders_first_and_then_stops_the_event(self):
        event = _Event(payload={"schema_version": 1})

        await self.plugin._meguri_isolate_and_render(event)

        self.assertEqual(self.rendered, [True])
        self.assertTrue(event.stopped)

    async def test_meguri_turn_still_stops_the_event_when_rendering_fails(self):
        async def failing(_event):
            raise RuntimeError("render failed")

        self.plugin._decorate_and_render = failing
        event = _Event(payload={"schema_version": 1})

        with self.assertRaises(RuntimeError):
            await self.plugin._meguri_isolate_and_render(event)

        self.assertTrue(event.stopped)

    async def test_non_meguri_turn_is_left_to_the_low_priority_handler(self):
        event = _Event()

        await self.plugin._meguri_isolate_and_render(event)

        self.assertEqual(self.rendered, [])
        self.assertFalse(event.stopped)

    async def test_low_priority_handler_skips_meguri_turns(self):
        event = _Event(payload={"schema_version": 1})

        await self.plugin._legacy_decorate_last(event)

        self.assertEqual(self.rendered, [])

    async def test_low_priority_handler_renders_other_turns(self):
        event = _Event()

        await self.plugin._legacy_decorate_last(event)

        self.assertEqual(self.rendered, [True])
        self.assertFalse(event.stopped)
