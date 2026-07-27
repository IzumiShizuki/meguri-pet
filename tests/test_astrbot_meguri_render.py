import json
import random
import tempfile
import unittest
from pathlib import Path

from adapters.astrbot.astrbot_plugin_chuanhuatong.meguri_render import (
    BackgroundPicker,
    background_group_for_payload,
    format_bilingual_pairs,
    normalize_gateway_payload,
    normalize_bilingual_font_size,
    parse_bilingual_pairs,
    parse_meguri_json,
    resolve_sprite_path,
    split_bilingual_text,
)


def response(**overrides):
    value = {
        "reply": "今天也辛苦啦。",
        "expression_tag": "happy",
        "expression_intensity": "medium",
        "voice_style": "cheerful",
        "memory_candidates": [],
    }
    value.update(overrides)
    return value


def payload(*, runtime_state=None, expression=None, llm_response=None):
    return {
        "response": llm_response or response(),
        "runtime_state": runtime_state or {},
        "expression": expression or {},
    }


class MeguriJsonParsingTests(unittest.TestCase):
    def test_parses_bare_five_field_json(self):
        parsed = parse_meguri_json(json.dumps(response(), ensure_ascii=False))

        self.assertIsNotNone(parsed)
        reply, render_payload = parsed
        self.assertEqual(reply, "今天也辛苦啦。")
        self.assertEqual(render_payload["response"]["expression_tag"], "happy")

    def test_parses_complete_json_fence(self):
        source = f"```json\n{json.dumps(response(), ensure_ascii=False)}\n```"

        parsed = parse_meguri_json(source)

        self.assertIsNotNone(parsed)
        self.assertEqual(parsed[0], "今天也辛苦啦。")

    def test_illegal_expression_values_fall_back_safely(self):
        source = json.dumps(response(expression_tag="background/night.png", expression_intensity="MAX"))

        _, render_payload = parse_meguri_json(source)

        self.assertEqual(render_payload["response"]["expression_tag"], "neutral")
        self.assertEqual(render_payload["response"]["expression_intensity"], "low")

    def test_rejects_non_contract_fields_and_surrounding_prose(self):
        with_background = response(background="night.png")

        self.assertIsNone(parse_meguri_json(json.dumps(with_background)))
        self.assertIsNone(parse_meguri_json(f"result: {json.dumps(response())}"))


class BilingualReplyTests(unittest.TestCase):
    def test_bilingual_font_size_uses_default_and_safe_bounds(self):
        self.assertEqual(normalize_bilingual_font_size(None), 42)
        self.assertEqual(normalize_bilingual_font_size("invalid"), 42)
        self.assertEqual(normalize_bilingual_font_size(True), 42)
        self.assertEqual(normalize_bilingual_font_size("48"), 48)
        self.assertEqual(normalize_bilingual_font_size(5), 18)
        self.assertEqual(normalize_bilingual_font_size(120), 64)

    def test_parses_canonical_pairs_and_preserves_content_colons(self):
        source = (
            "【晚上 12:30 也不要太勉强。】\n"
            "【夜の12:30でも、無理しすぎないでね。】\n\n"
            "【早点休息吧。】\n"
            "【早めに休もうね。】"
        )

        pairs = parse_bilingual_pairs(source)

        self.assertEqual(
            pairs,
            [
                ("晚上 12:30 也不要太勉强。", "夜の12:30でも、無理しすぎないでね。"),
                ("早点休息吧。", "早めに休もうね。"),
            ],
        )
        self.assertEqual(format_bilingual_pairs(pairs or []), source)

    def test_preserves_translation_original_order_without_visible_language_labels(self):
        source = "【今天也辛苦了。】\n【今日もお疲れさま。】"

        pairs = parse_bilingual_pairs(source)

        self.assertEqual(pairs, [("今天也辛苦了。", "今日もお疲れさま。")])
        self.assertEqual(
            format_bilingual_pairs(pairs or []),
            "【今天也辛苦了。】\n【今日もお疲れさま。】",
        )

    def test_rejects_incomplete_pairs_and_stray_prose_without_dropping_it(self):
        self.assertIsNone(parse_bilingual_pairs("【今天也辛苦了。】"))
        self.assertIsNone(
            parse_bilingual_pairs(
                "下面是双语回复：\n【今天也辛苦了。】\n【今日もお疲れさま。】"
            )
        )
        self.assertIsNone(
            parse_bilingual_pairs(
                "【第一句。】\n【第二句。】\n【二文目です。】"
            )
        )

    def test_long_text_splits_only_between_complete_pairs(self):
        pairs = [
            ("第一句中文翻译。", "一つ目の日本語原文です。"),
            ("第二句中文翻译。", "二つ目の日本語原文です。"),
            ("第三句中文翻译。", "三つ目の日本語原文です。"),
        ]
        source = format_bilingual_pairs(pairs)

        chunks = split_bilingual_text(source, max_chars=28)

        self.assertIsNotNone(chunks)
        self.assertEqual(
            [pair for chunk in chunks or [] for pair in (parse_bilingual_pairs(chunk) or [])],
            pairs,
        )
        self.assertTrue(all(len(parse_bilingual_pairs(chunk) or []) == 1 for chunk in chunks or []))

    def test_short_pairs_still_get_one_panel_each(self):
        pairs = [("好。", "うん。"), ("走吧。", "行こう。")]

        chunks = split_bilingual_text(format_bilingual_pairs(pairs), max_chars=10_000)

        self.assertEqual(
            chunks,
            [format_bilingual_pairs([pair]) for pair in pairs],
        )

    def test_single_oversized_pair_remains_atomic(self):
        source = (
            "【这是一句明显超过测试阈值、但仍然不能与原文拆开的中文翻译。】\n"
            "【これはテストの上限を超えても、翻訳と分離してはいけない原文です。】"
        )

        self.assertEqual(split_bilingual_text(source, max_chars=10), [source])


class GatewayPayloadTests(unittest.TestCase):
    def test_resolved_expression_and_outfit_are_authoritative(self):
        normalized = normalize_gateway_payload(
            payload(
                runtime_state={"mode": "work", "outfit_code": "01"},
                expression={
                    "expression_tag": "angry",
                    "expression_intensity": "high",
                    "outfit_code": "03",
                    "expression_code": "014",
                    "sprite_file": "ce03014l.png",
                },
            )
        )

        self.assertEqual(normalized["response"]["expression_tag"], "happy")
        self.assertEqual(normalized["expression"]["expression_tag"], "angry")
        self.assertEqual(normalized["expression"]["expression_intensity"], "high")
        self.assertEqual(normalized["expression"]["outfit_code"], "03")
        self.assertEqual(normalized["expression"]["expression_code"], "014")


class SpriteResolutionTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        (self.root / "sprites" / "01").mkdir(parents=True)
        (self.root / "sprites" / "02").mkdir(parents=True)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_prefers_medium_asset_for_core_large_filename(self):
        medium = self.root / "sprites" / "01" / "ce01003m.png"
        large = self.root / "sprites" / "01" / "ce01003l.png"
        medium.touch()
        large.touch()
        render_payload = payload(
            runtime_state={"outfit_code": "01"},
            expression={
                "expression_tag": "happy",
                "expression_intensity": "medium",
                "outfit_code": "01",
                "expression_code": "003",
                "sprite_file": "ce01003l.png",
            },
        )

        self.assertEqual(resolve_sprite_path(self.root, render_payload), medium.resolve())

    def test_missing_expression_asset_falls_back_to_neutral_same_outfit(self):
        neutral = self.root / "sprites" / "02" / "ce02001m.png"
        neutral.touch()
        render_payload = payload(
            runtime_state={"outfit_code": "02"},
            expression={
                "expression_tag": "happy",
                "expression_intensity": "medium",
                "outfit_code": "02",
            },
        )

        self.assertEqual(resolve_sprite_path(self.root, render_payload), neutral.resolve())

    def test_rejects_disabled_outfits_and_path_traversal(self):
        (self.root / "sprites" / "01" / "ce01001m.png").touch()
        for outfit in ("07", "08"):
            render_payload = payload(
                runtime_state={"outfit_code": outfit},
                expression={"outfit_code": outfit, "expression_code": "001"},
            )
            self.assertIsNone(resolve_sprite_path(self.root, render_payload))

        traversing = payload(
            runtime_state={"outfit_code": "01"},
            expression={
                "outfit_code": "01",
                "expression_code": "001",
                "sprite_file": "../ce01001l.png",
            },
        )
        self.assertIsNone(resolve_sprite_path(self.root, traversing))


class BackgroundSelectionTests(unittest.TestCase):
    def test_maps_runtime_modes_times_and_event_outfits(self):
        cases = [
            ({"mode": "work", "outfit_code": "01", "local_time": "2026-07-23T09:00:00+08:00"}, "work_day"),
            ({"mode": "private", "outfit_code": "02", "is_holiday": True, "local_time": "2026-07-25T10:00:00+08:00"}, "private_day"),
            ({"mode": "private", "outfit_code": "03", "local_time": "2026-07-23T20:00:00+08:00"}, "private_evening"),
            ({"mode": "sleep", "outfit_code": "04", "local_time": "2026-07-23T23:00:00+08:00"}, "sleep_night"),
            (
                {"mode": "event", "outfit_code": "05", "local_time": "2026-07-23T12:00:00+08:00"},
                "event_pool_day",
            ),
            (
                {"mode": "event", "outfit_code": "05", "local_time": "2026-07-23T20:00:00+08:00"},
                "event_pool_evening",
            ),
            (
                {"mode": "event", "outfit_code": "06", "local_time": "2026-07-23T23:00:00+08:00"},
                "event_shrine_night",
            ),
        ]
        for runtime, expected in cases:
            with self.subTest(expected=expected):
                self.assertEqual(background_group_for_payload(payload(runtime_state=runtime)), expected)

    def test_uses_time_and_holiday_when_outfit_is_absent(self):
        holiday_day = payload(
            runtime_state={"local_time": "2026-07-25T12:30:00+08:00", "is_holiday": True}
        )
        night = payload(runtime_state={"local_time": "2026-07-25T23:30:00+08:00"})

        self.assertEqual(background_group_for_payload(holiday_day), "private_day")
        self.assertEqual(background_group_for_payload(night), "sleep_night")

    def test_picker_avoids_immediate_repeat_and_ignores_traversal(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            background_dir = root / "backgrounds"
            background_dir.mkdir()
            first = background_dir / "work-1.png"
            second = background_dir / "work-2.png"
            first.touch()
            second.touch()
            manifest = {
                "backgrounds": {
                    "work_day": [
                        "backgrounds/work-1.png",
                        "../outside.png",
                        "backgrounds/work-2.png",
                    ]
                }
            }
            picker = BackgroundPicker(root, manifest, rng=random.Random(7))
            render_payload = payload(
                runtime_state={
                    "mode": "work",
                    "outfit_code": "01",
                    "local_time": "2026-07-23T10:00:00+08:00",
                }
            )

            selected_first = picker.pick(render_payload, "session")
            selected_second = picker.pick(render_payload, "session")

            self.assertIn(selected_first, {first.resolve(), second.resolve()})
            self.assertIn(selected_second, {first.resolve(), second.resolve()})
            self.assertNotEqual(selected_first, selected_second)


if __name__ == "__main__":
    unittest.main()
