import importlib
import sys
import types
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from adapters.astrbot.astrbot_plugin_chuanhuatong.meguri_render import (
    format_bilingual_pairs,
)


MODULE_NAME = "adapters.astrbot.astrbot_plugin_chuanhuatong.main"


class _Logger:
    def __getattr__(self, _name):
        return lambda *_args, **_kwargs: None


class _Filter:
    def __getattr__(self, _name):
        return lambda *_args, **_kwargs: lambda function: function


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
    def __init__(self):
        self.sent_parts = []

    async def send(self, chain):
        self.sent_parts.append(list(chain.parts))


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


class AstrBotConversationPaginationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        sys.modules.pop(MODULE_NAME, None)
        self.module_patch = patch.dict(sys.modules, _astrbot_modules())
        self.module_patch.start()
        self.addCleanup(self.module_patch.stop)
        module = importlib.import_module(MODULE_NAME)
        self.plugin = module.ChuanHuaTongPlugin.__new__(module.ChuanHuaTongPlugin)
        self.plugin._cfg_obj = {
            "meguri_bilingual_enabled": True,
            "render_char_threshold": 42,
        }
        self.plugin._emotion_meta = lambda: {}
        self.plugin._schedule_cleanup = lambda *_args, **_kwargs: None
        self.temp_dir = TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)

    async def asyncTearDown(self):
        sys.modules.pop(MODULE_NAME, None)

    async def test_continuation_images_use_their_matching_page_text(self):
        translated = "这是一句明显超过单张对话卡容量的翻译，后续内容必须在下一张图片里继续。"
        original = "これは一枚のカードに収まらない原文で、続きは次の画像に正しく表示されなければなりません。"
        text = format_bilingual_pairs([(translated, original)])
        rendered_texts = []

        async def render_page(page_text, _emotion, _session_id, _payload):
            rendered_texts.append(page_text)
            output = Path(self.temp_dir.name) / f"page-{len(rendered_texts)}.jpeg"
            output.write_text(page_text, encoding="utf-8")
            return str(output)

        self.plugin._render_with_fallback = render_page
        event = _Event()

        delivered = await self.plugin._render_split_text(
            text, "neutral", event, "session-1", {"schema_version": 1}
        )

        self.assertTrue(delivered)
        self.assertGreater(len(rendered_texts), 1)
        self.assertEqual(len(set(rendered_texts)), len(rendered_texts))
        sent_paths = [parts[0][1] for parts in event.sent_parts]
        self.assertEqual(
            [Path(path).read_text(encoding="utf-8") for path in sent_paths],
            rendered_texts,
        )

    async def test_failed_page_falls_back_in_its_original_position(self):
        translated = "第一页之后还需要第二页，这一页失败时必须在原位置发送文本。"
        original = "一ページ目の後にも続きがあり、失敗したページは同じ位置でテキスト送信される必要があります。"
        text = format_bilingual_pairs([(translated, original)])
        render_count = 0
        attempted_texts = []

        async def render_page(page_text, _emotion, _session_id, _payload):
            nonlocal render_count
            render_count += 1
            attempted_texts.append(page_text)
            if render_count == 2:
                return None
            output = Path(self.temp_dir.name) / f"page-{render_count}.jpeg"
            output.write_text(page_text, encoding="utf-8")
            return str(output)

        self.plugin._render_with_fallback = render_page
        event = _Event()

        delivered = await self.plugin._render_split_text(
            text, "neutral", event, "session-1", {"schema_version": 1}
        )

        self.assertTrue(delivered)
        self.assertGreater(len(event.sent_parts), 1)
        self.assertEqual(event.sent_parts[1], [("text", f"{attempted_texts[1]}\n")])
