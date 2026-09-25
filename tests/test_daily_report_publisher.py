import json
import tempfile
import unittest
from pathlib import Path

from tools.publish_daily_report import PublishError, build_envelope, write_local_notice


class DailyReportPublisherTests(unittest.TestCase):
    def test_builds_deterministic_bilingual_delivery_without_llm(self):
        envelope = build_envelope(
            "bilibili",
            {
                "status": "ready",
                "date": "2026-07-24",
                "generated_at": "2026-07-25T02:36:47+08:00",
                "data_source": "account_mcp",
                "sync_status": "success",
                "unique_videos": 64,
                "total_visits": 64,
                "summary": "fixture summary",
                "visual_payload": {
                    "schema_version": 1,
                    "template": "bilibili_daily_v1",
                    "statistics": {"video_count": 64},
                },
            },
            "# fixture\n",
        )

        self.assertEqual(envelope["report_id"], "bilibili:2026-07-24")
        self.assertEqual(envelope["render_payload"]["statistics"]["video_count"], 64)
        self.assertEqual(
            envelope["delivery_text"].splitlines(),
            [
                "【兄さん，2026-07-24 的 Bilibili 观看元数据日报送到了。】",
                "【兄さん、2026-07-24のBilibili視聴メタデータ日報が届いたよ。】",
                "【共 64 个视频、64 条观看记录；数据源：账号只读 MCP。】",
                "【動画は64本、視聴記録は64件。データソース：アカウント読み取り専用MCP。】",
            ],
        )
        self.assertNotIn("中文", envelope["delivery_text"])
        self.assertNotIn("日本語", envelope["delivery_text"])

    def test_local_notice_omits_full_markdown(self):
        envelope = build_envelope(
            "bilibili",
            {
                "status": "empty",
                "date": "2026-07-24",
                "generated_at": "2026-07-25T02:36:47+08:00",
                "data_source": "browser_history",
                "sync_status": "success",
                "unique_videos": 0,
                "total_visits": 0,
                "summary": "当天没有记录。",
            },
            "# empty\n",
        )
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "latest.json"
            write_local_notice(envelope, target)
            notice = json.loads(target.read_text(encoding="utf-8"))
        self.assertNotIn("markdown", notice)
        self.assertEqual(notice["markdown_url"], "/reports/daily/bilibili-2026-07-24.md")

    def test_rejects_unavailable_report(self):
        with self.assertRaises(PublishError):
            build_envelope(
                "bilibili",
                {
                    "status": "unavailable",
                    "date": "2026-07-24",
                    "generated_at": "2026-07-25T02:36:47+08:00",
                    "data_source": "unavailable",
                    "sync_status": "unavailable",
                    "unique_videos": 0,
                    "total_visits": 0,
                    "summary": "unavailable",
                },
                "# unavailable\n",
            )

    def test_rejects_unknown_visual_payload_contract(self):
        with self.assertRaises(PublishError):
            build_envelope(
                "bilibili",
                {
                    "status": "ready",
                    "date": "2026-07-24",
                    "generated_at": "2026-07-25T02:36:47+08:00",
                    "data_source": "account_mcp",
                    "sync_status": "success",
                    "unique_videos": 1,
                    "total_visits": 1,
                    "summary": "fixture",
                    "visual_payload": {"schema_version": 99, "template": "unknown"},
                },
                "# fixture\n",
            )
