import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image, ImageChops, ImageDraw

from adapters.astrbot.astrbot_plugin_meguri_gateway.bilibili_daily_card import (
    BilibiliDailyCardError,
    DEFAULT_TEMPLATE,
    render_bilibili_daily_card,
)


def fixture_payload() -> dict:
    return {
        "schema_version": 1,
        "template": "bilibili_daily_v1",
        "date": "2026-07-29",
        "data_source": "account_mcp",
        "source_label": "B站账号历史（时长为估算）",
        "statistics": {
            "video_count": 18,
            "record_count": 21,
            "estimated_watch_seconds": 13_320,
            "average_completion_rate": 0.68,
            "completed_videos": 6,
            "primary_topics": ["国际局势", "科技产业"],
        },
        "top_commentary": [
            {
                "title": "为什么年轻人越来越谨慎消费：收入预期与生活成本的变化",
                "author_name": "观察者测试UP主",
                "topic": "宏观经济",
                "completion_rate": 0.92,
                "visit_count": 1,
            },
            {
                "title": "芯片产业竞争背后的政策变化",
                "author_name": "科技评论",
                "topic": "科技产业",
                "completion_rate": 0.81,
                "visit_count": 1,
            },
        ],
        "interest_tags": ["宏观经济", "国际局势", "科技产业", "社会观察", "知识", "历史文化"],
        "overall_summary": (
            "昨日共观看18个视频，估算时长3小时42分，平均播放进度约68%。"
            "兴趣主要集中在宏观经济、国际局势和科技产业。"
            "从标题与分区推测，时评内容更多涉及消费预期与产业竞争。"
            "以上仅为元数据观察，未读取字幕或视频正文。"
        ),
        "analysis_basis": "metadata_only",
    }


class BilibiliDailyCardTests(unittest.TestCase):
    def _record_text(self, payload):
        records = []
        original_factory = ImageDraw.Draw

        class RecordingDraw:
            def __init__(self, image):
                self.draw = original_factory(image)

            def __getattr__(self, name):
                return getattr(self.draw, name)

            def text(self, position, text, **kwargs):
                records.append((text, self.draw.textbbox(position, text, font=kwargs["font"])))
                return self.draw.text(position, text, **kwargs)

        with tempfile.TemporaryDirectory() as directory:
            with patch("adapters.astrbot.astrbot_plugin_meguri_gateway.bilibili_daily_card.ImageDraw.Draw",
                       side_effect=RecordingDraw):
                render_bilibili_daily_card(payload, Path(directory) / "aligned.png")
        return records

    def test_renders_the_template_without_changing_canvas_size(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "daily.png"
            render_bilibili_daily_card(fixture_payload(), output)
            with Image.open(DEFAULT_TEMPLATE) as template, Image.open(output) as rendered:
                self.assertEqual((1086, 1448), rendered.size)
                difference = ImageChops.difference(template.convert("RGB"), rendered.convert("RGB"))
                self.assertIsNotNone(difference.getbbox())
                left, top, right, bottom = difference.getbbox()
                self.assertGreaterEqual(left, 70)
                self.assertGreaterEqual(top, 350)
                self.assertLessEqual(right, 1000)
                self.assertLessEqual(bottom, 1448)

    def test_rejects_a_path_like_date(self):
        payload = fixture_payload()
        payload["date"] = "../outside"
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(BilibiliDailyCardError):
                render_bilibili_daily_card(payload, Path(directory) / "daily.png")

    def test_visible_single_and_wrapped_tags_are_centered_inside_template_pills(self):
        payload = fixture_payload()
        payload["analysis_basis"] = "content_evidence"
        payload["interest_tags"] = ["Java并发", "日语授受动词", "人工智能视频内容理解",
                                    "游戏隐藏关卡探索路线", "音乐编曲与混音技巧", "历史文化"]
        records = self._record_text(payload)
        # Measured template pill interiors, independently of text-rendering code.
        boxes = ((608, 623, 798, 681), (829, 623, 1006, 681),
                 (608, 730, 798, 788), (829, 730, 1006, 788),
                 (608, 836, 798, 894), (829, 836, 1006, 894))
        for left, top, right, bottom in boxes:
            with self.subTest(pill=(left, top)):
                lines = [b for _, b in records if left <= b[0] < right and top <= b[1] < bottom]
                self.assertTrue(lines)
                ink = (min(b[0] for b in lines), min(b[1] for b in lines),
                       max(b[2] for b in lines), max(b[3] for b in lines))
                self.assertLessEqual(ink[2], right)
                self.assertLessEqual(ink[3], bottom)
                self.assertAlmostEqual((left + right) / 2, (ink[0] + ink[2]) / 2, delta=1)
                self.assertAlmostEqual((top + bottom) / 2, (ink[1] + ink[3]) / 2, delta=1)

    def test_long_subjects_and_summary_stay_clear_of_rules_and_decorations(self):
        payload = fixture_payload()
        payload["analysis_basis"] = "content_evidence"
        payload["top_commentary"] = [dict(summary="比较线程调度、共享状态与阻塞任务吞吐，说明虚拟线程与固定线程池的使用边界。" * 3,
                                         topic="Java并发", basis_label="字幕", author_name="演示UP") for _ in range(3)]
        payload["overall_summary"] = "按已提供的资料归纳具体内容与观看方向，完整信息请查看日报。" * 15
        records = self._record_text(payload)
        for text, bounds in records:
            if 141 <= bounds[0] <= 143 and 600 < bounds[1] < 940:
                inside_slot = any(
                    142 <= bounds[0] and bounds[2] <= 517
                    and top <= bounds[1] and bounds[3] <= bottom
                    for index in range(3)
                    for top, bottom in ((612 + 112 * index, 640 + 112 * index),
                                        (652 + 112 * index, 680 + 112 * index),
                                        (686 + 112 * index, 708 + 112 * index)))
                self.assertTrue(inside_slot, (text, bounds))
            if bounds[0] == 78 and bounds[1] > 1080:
                self.assertLessEqual(bounds[2], 675)
                self.assertLessEqual(bounds[3], 1295)
        summary_lines = [text for text, bounds in records if bounds[0] == 78 and bounds[1] > 1080]
        self.assertEqual(6, len(summary_lines))
        self.assertTrue(summary_lines[-1].endswith("…"))


if __name__ == "__main__":
    unittest.main()
