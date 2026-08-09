import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from adapters.astrbot.astrbot_plugin_meguri_gateway.daily_reports import DailyReportPoller


class _Core:
    def __init__(self, report):
        self.report = report

    async def latest_daily_report(self, _kind):
        return self.report


class AstrBotDailyReportTests(unittest.IsolatedAsyncioTestCase):
    async def test_delivers_each_report_once_per_target_across_restarts(self):
        report = {
            "report_id": "bilibili:2026-07-24",
            "published_at": "2026-07-25T02:37:00+08:00",
            "delivery_text": "【中文】\n【日本語】",
            "render_payload": {
                "schema_version": 1,
                "template": "bilibili_daily_v1",
                "date": "2026-07-24",
            },
        }
        sent = []

        async def send(target, text, render_payload):
            sent.append((target, text, render_payload))
            return True

        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state.json"
            kwargs = dict(
                targets=["meguri:FriendMessage:user-openid"],
                kinds=["bilibili"],
                state_file=state,
                now=lambda: datetime(2026, 7, 25, 3, tzinfo=timezone.utc),
            )
            first = DailyReportPoller(_Core(report), send, **kwargs)
            self.assertEqual((await first.poll_once())["sent"], 1)
            restarted = DailyReportPoller(_Core(report), send, **kwargs)
            self.assertEqual((await restarted.poll_once())["sent"], 0)

        self.assertEqual(
            sent,
            [
                (
                    "meguri:FriendMessage:user-openid",
                    "【中文】\n【日本語】",
                    report["render_payload"],
                )
            ],
        )

    async def test_failed_target_remains_pending(self):
        report = {
            "report_id": "bilibili:2026-07-24",
            "published_at": "2026-07-25T02:37:00+08:00",
            "delivery_text": "fixture",
        }

        async def send(_target, _text, _render_payload):
            return False

        with tempfile.TemporaryDirectory() as directory:
            poller = DailyReportPoller(
                _Core(report),
                send,
                targets=["meguri:FriendMessage:user-openid"],
                kinds=["bilibili"],
                state_file=Path(directory) / "state.json",
                now=lambda: datetime(2026, 7, 25, 3, tzinfo=timezone.utc),
            )
            self.assertEqual((await poller.poll_once())["sent"], 0)
            self.assertIsNone(poller.state.delivered("meguri:FriendMessage:user-openid", "bilibili"))

    async def test_stale_report_is_not_sent_or_marked(self):
        report = {
            "report_id": "bilibili:2026-07-20",
            "published_at": "2026-07-20T02:37:00+08:00",
            "delivery_text": "fixture",
        }
        sent = []
        with tempfile.TemporaryDirectory() as directory:
            poller = DailyReportPoller(
                _Core(report),
                lambda target, text, render_payload: sent.append(
                    (target, text, render_payload)
                )
                or True,
                targets=["meguri:FriendMessage:user-openid"],
                kinds=["bilibili"],
                state_file=Path(directory) / "state.json",
                now=lambda: datetime(2026, 7, 25, 3, tzinfo=timezone.utc),
            )
            stats = await poller.poll_once()
        self.assertEqual(stats["skipped"], 1)
        self.assertEqual(sent, [])

    async def test_rejects_an_oversized_render_payload_before_sending(self):
        report = {
            "report_id": "bilibili:2026-07-24",
            "published_at": "2026-07-25T02:37:00+08:00",
            "delivery_text": "fixture",
            "render_payload": {"text": "x" * (33 * 1024)},
        }
        with tempfile.TemporaryDirectory() as directory:
            poller = DailyReportPoller(
                _Core(report),
                lambda _target, _text, _payload: True,
                targets=["meguri:FriendMessage:user-openid"],
                kinds=["bilibili"],
                state_file=Path(directory) / "state.json",
                now=lambda: datetime(2026, 7, 25, 3, tzinfo=timezone.utc),
            )
            with self.assertRaisesRegex(ValueError, "oversized"):
                await poller.poll_once()
