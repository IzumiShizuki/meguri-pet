from __future__ import annotations

import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
from contextlib import closing
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from zoneinfo import ZoneInfo

from tools.generate_bilibili_browser_report import read_sync_status


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = PROJECT_ROOT / "tools" / "generate_bilibili_browser_report.py"
CHROMIUM_EPOCH_SECONDS = 11_644_473_600


def chrome_time(value: str) -> int:
    instant = datetime.fromisoformat(value).astimezone(ZoneInfo("UTC"))
    return int((instant.timestamp() + CHROMIUM_EPOCH_SECONDS) * 1_000_000)


def create_history(path: Path, urls: list[tuple[int, str, str]], visits: list[tuple[int, int, str]]) -> None:
    with closing(sqlite3.connect(path)) as database:
        database.executescript(
            """
            CREATE TABLE urls (id INTEGER PRIMARY KEY, url TEXT NOT NULL, title TEXT, visit_count INTEGER DEFAULT 0);
            CREATE TABLE visits (id INTEGER PRIMARY KEY, url INTEGER NOT NULL, visit_time INTEGER NOT NULL);
            """
        )
        database.executemany("INSERT INTO urls(id, url, title) VALUES (?, ?, ?)", urls)
        database.executemany(
            "INSERT INTO visits(id, url, visit_time) VALUES (?, ?, ?)",
            [(visit_id, url_id, chrome_time(visited_at)) for visit_id, url_id, visited_at in visits],
        )
        database.commit()


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class McpFixtureHandler(BaseHTTPRequestHandler):
    token = "fixture-mcp-token"
    calls: list[dict] = []

    def log_message(self, format: str, *args: object) -> None:
        return

    def do_POST(self) -> None:
        if self.headers.get("Authorization") != f"Bearer {self.token}":
            self.send_error(401)
            return
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        payload = json.loads(body)
        self.__class__.calls.append(payload)
        method = payload.get("method")
        if method == "notifications/initialized":
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if method == "initialize":
            result = {
                "jsonrpc": "2.0",
                "id": payload["id"],
                "result": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": "fixture", "version": "1"},
                },
            }
        elif method == "tools/call":
            arguments = payload["params"]["arguments"]
            records = [
                {
                    "bvid": "BV1xx411c7mD",
                    "title": "AI 产业观察：账号历史第一条",
                    "author_name": "测试 UP",
                    "author_mid": 42,
                    "view_at": int(datetime.fromisoformat("2026-07-22T09:00:00+08:00").timestamp()),
                    "progress": 90,
                    "duration": 100,
                    "tag_name": "知识",
                    "main_category": "科技",
                    "business": "archive",
                },
                {
                    "bvid": "BV1Q541167Qg",
                    "title": "账号历史第二条",
                    "author_name": "另一个 UP",
                    "author_mid": 43,
                    "view_at": int(datetime.fromisoformat("2026-07-22T18:00:00+08:00").timestamp()),
                    "progress": -1,
                    "duration": 60,
                    "tag_name": "生活",
                    "main_category": "生活",
                    "business": "archive",
                },
            ]
            structured = {
                "status": "success",
                "data": {
                    "status": "success",
                    "data": {"records": records, "total": len(records), "current": arguments["page"]},
                },
            }
            result = {
                "jsonrpc": "2.0",
                "id": payload["id"],
                "result": {"content": [], "structuredContent": structured, "isError": False},
            }
        else:
            self.send_error(400)
            return
        encoded = json.dumps(result).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Mcp-Session-Id", "fixture-session")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_DELETE(self) -> None:
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()


class BilibiliBrowserReportIntegrationTest(unittest.TestCase):
    def test_sync_status_rejects_a_different_date_as_stale(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            status_file = Path(temporary) / "state.json"
            status_file.write_text(
                json.dumps({"target_date": "2026-07-21", "status": "success"}), encoding="utf-8"
            )

            status, note = read_sync_status(status_file, datetime.fromisoformat("2026-07-22").date())

            self.assertEqual("stale", status)
            self.assertIn("不属于目标日期", note)

    def test_prefers_loopback_read_only_mcp_and_generates_metadata_only_account_report(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary)
            project_root = workspace / "project"
            project_root.mkdir()
            token_file = workspace / "secrets" / "bhf-token.txt"
            token_file.parent.mkdir()
            token_file.write_text(McpFixtureHandler.token, encoding="utf-8")
            sync_status_file = workspace / "daily-sync-latest.json"
            sync_status_file.write_text(
                json.dumps({"target_date": "2026-07-22", "status": "success"}), encoding="utf-8"
            )
            McpFixtureHandler.calls = []
            server = ThreadingHTTPServer(("127.0.0.1", 0), McpFixtureHandler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                completed = subprocess.run(
                    [
                        sys.executable,
                        str(SCRIPT),
                        "--project-root",
                        str(project_root),
                        "--date",
                        "2026-07-22",
                        "--timezone",
                        "Asia/Shanghai",
                        "--mcp-url",
                        f"http://127.0.0.1:{server.server_port}/mcp/",
                        "--mcp-token-file",
                        str(token_file),
                        "--sync-status-file",
                        str(sync_status_file),
                        "--history",
                        f"Missing={workspace / 'missing-history'}",
                    ],
                    check=True,
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                )
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)

            report = json.loads(completed.stdout)
            self.assertEqual("account_mcp", report["data_source"])
            self.assertEqual("success", report["sync_status"])
            self.assertEqual(2, report["unique_videos"])
            self.assertEqual("not_requested", report["videos"][0]["content_summary_status"])
            visual = report["visual_payload"]
            self.assertEqual("bilibili_daily_v1", visual["template"])
            self.assertEqual(2, visual["statistics"]["video_count"])
            self.assertEqual(150, visual["statistics"]["estimated_watch_seconds"])
            self.assertEqual(0.95, visual["statistics"]["average_completion_rate"])
            self.assertEqual("科技产业", visual["top_commentary"][0]["topic"])
            self.assertIn("未读取字幕或视频正文", visual["overall_summary"])
            self.assertIn("只读 MCP", report["boundary"])
            tool_call = next(call for call in McpFixtureHandler.calls if call.get("method") == "tools/call")
            self.assertEqual("query_history_records", tool_call["params"]["name"])
            self.assertEqual("20260722-20260722", tool_call["params"]["arguments"]["date_range"])
            self.assertEqual(100, tool_call["params"]["arguments"]["size"])
            markdown = (project_root / "reports/daily/bilibili-2026-07-22.md").read_text(encoding="utf-8")
            self.assertIn("数据源：账号历史（BilibiliHistoryFetcher 只读 MCP）", markdown)
            self.assertIn("同步状态：success", markdown)
            self.assertIn("[AI 产业观察：账号历史第一条](https://www.bilibili.com/video/BV1xx411c7mD/)", markdown)

    def test_unavailable_account_mcp_explicitly_falls_back_to_browser_history(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary)
            project_root = workspace / "project"
            project_root.mkdir()
            history = workspace / "History"
            create_history(
                history,
                [(1, "https://www.bilibili.com/video/BV1xx411c7mD/", "浏览器兜底")],
                [(1, 1, "2026-07-22T09:00:00+08:00")],
            )
            token_file = workspace / "bhf-token.txt"
            token_file.write_text("unreachable-token", encoding="utf-8")
            sync_status_file = workspace / "daily-sync-latest.json"
            sync_status_file.write_text(
                json.dumps({"target_date": "2026-07-22", "status": "error"}), encoding="utf-8"
            )
            unused = ThreadingHTTPServer(("127.0.0.1", 0), McpFixtureHandler)
            unused_port = unused.server_port
            unused.server_close()

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--project-root",
                    str(project_root),
                    "--date",
                    "2026-07-22",
                    "--mcp-url",
                    f"http://127.0.0.1:{unused_port}/mcp/",
                    "--mcp-token-file",
                    str(token_file),
                    "--mcp-timeout-seconds",
                    "0.5",
                    "--sync-status-file",
                    str(sync_status_file),
                    "--history",
                    f"Chrome/Test={history}",
                ],
                check=True,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            report = json.loads(completed.stdout)
            self.assertEqual("browser_history_fallback", report["data_source"])
            self.assertEqual("error", report["sync_status"])
            self.assertIsNone(report["visual_payload"]["statistics"]["estimated_watch_seconds"])
            self.assertIsNone(report["visual_payload"]["statistics"]["average_completion_rate"])
            self.assertEqual("unavailable", report["sources"][0]["status"])
            self.assertEqual("ready", report["sources"][1]["status"])
            markdown = (project_root / "reports/daily/bilibili-2026-07-22.md").read_text(encoding="utf-8")
            self.assertIn("账号 MCP 不可用后的显式降级", markdown)
            self.assertIn("同步状态：error", markdown)

    def test_deduplicates_bvid_and_never_mutates_history_fixtures(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            chrome = root / "Chrome-History"
            edge = root / "Edge-History"
            create_history(
                chrome,
                [
                    (1, "https://www.bilibili.com/video/BV1xx411c7mD/?spm_id_from=333", "第一条_哔哩哔哩_bilibili"),
                    (2, "https://www.bilibili.com/video/BV1Q541167Qg", "第二条"),
                    (3, "https://www.bilibili.com/read/cv123", "专栏"),
                ],
                [
                    (1, 1, "2026-07-22T09:00:00+08:00"),
                    (2, 1, "2026-07-22T10:30:00+08:00"),
                    (3, 2, "2026-07-22T12:00:00+08:00"),
                    (4, 3, "2026-07-22T13:00:00+08:00"),
                    (5, 2, "2026-07-21T23:59:59+08:00"),
                ],
            )
            create_history(
                edge,
                [(1, "https://m.bilibili.com/video/BV1xx411c7mD?from=search", "第一条（移动页）")],
                [(1, 1, "2026-07-22T18:15:00+08:00")],
            )
            before = {chrome: digest(chrome), edge: digest(edge)}

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--project-root",
                    str(root),
                    "--date",
                    "2026-07-22",
                    "--timezone",
                    "Asia/Shanghai",
                    "--history",
                    f"Chrome/Test={chrome}",
                    "--history",
                    f"Edge/Test={edge}",
                ],
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            report = json.loads(completed.stdout)

            self.assertEqual(0, completed.returncode)
            self.assertEqual("ready", report["status"])
            self.assertEqual(2, report["unique_videos"])
            self.assertEqual(4, report["total_visits"])
            first = next(item for item in report["videos"] if item["bvid"] == "BV1xx411c7mD")
            self.assertEqual(3, first["visit_count"])
            self.assertEqual(["Chrome/Test", "Edge/Test"], first["sources"])
            self.assertIn("页面访问不代表视频已播放、看完", report["boundary"])
            self.assertEqual(before, {chrome: digest(chrome), edge: digest(edge)})

            markdown = root / "reports" / "daily" / "bilibili-2026-07-22.md"
            self.assertTrue(markdown.is_file())
            content = markdown.read_text(encoding="utf-8")
            self.assertIn("# 2026-07-22 Bilibili 本机浏览器访问日报", content)
            self.assertIn("[第一条（移动页）](https://www.bilibili.com/video/BV1xx411c7mD/)", content)
            self.assertNotIn("专栏", content)

    def test_explicit_missing_history_fails_closed_without_discovering_real_browser(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            missing = root / "does-not-exist"
            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--project-root",
                    str(root),
                    "--date",
                    "2026-07-22",
                    "--history",
                    f"Fixture={missing}",
                ],
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            report = json.loads(completed.stdout)

            self.assertEqual(2, completed.returncode)
            self.assertEqual("unavailable", report["status"])
            self.assertEqual(0, report["total_visits"])
            self.assertEqual("unavailable", report["sources"][0]["status"])
            self.assertTrue((root / "reports" / "daily" / "bilibili-2026-07-22.md").is_file())


if __name__ == "__main__":
    unittest.main()
