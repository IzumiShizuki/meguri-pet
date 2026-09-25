import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageChops

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


if __name__ == "__main__":
    unittest.main()
