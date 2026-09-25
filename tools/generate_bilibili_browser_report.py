#!/usr/bin/env python3
"""Generate a metadata-only Bilibili daily digest from local read-only data.

Privacy boundary:
- prefers BilibiliHistoryFetcher's local read-only MCP service;
- falls back to Chrome/Edge History SQLite only when the account database is unavailable;
- never reads Cookies, Login Data, Cache, or calls Bilibili account APIs itself;
- never downloads video pages or media;
- never performs content/subtitle summarization without an explicit future selection.

The script uses only the Python standard library. Explicit ``--history``
arguments disable local profile discovery. The account service is used only
when both ``--mcp-url`` and ``--mcp-token-file`` are supplied, which keeps
automated tests isolated from the user's real data.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sqlite3
import sys
import tempfile
from collections.abc import Iterable, Sequence
from contextlib import closing
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zoneinfo import ZoneInfo


CHROMIUM_EPOCH = datetime(1601, 1, 1, tzinfo=timezone.utc)
BVID_PATH = re.compile(r"^/video/(BV[0-9A-Za-z]{10})(?:/|$)", re.IGNORECASE)
BVID_VALUE = re.compile(r"^BV[0-9A-Za-z]{10}$", re.IGNORECASE)
ALLOWED_BILIBILI_HOSTS = frozenset({"www.bilibili.com", "m.bilibili.com"})
BROWSER_BOUNDARY = (
    "本报告仅统计本机 Chrome/Edge 浏览器 History 中的 Bilibili 视频页面访问；"
    "页面访问不代表视频已播放、看完，也无法推断实际观看时长；"
    "不包含无痕窗口、其他设备或 Bilibili App。"
)
ACCOUNT_BOUNDARY = (
    "本报告只通过 BilibiliHistoryFetcher 的本机只读 MCP 获取已同步账号观看历史元数据；"
    "Meguri 不读取 Cookie 或原始账号库、不会直接调用 Bilibili 账号接口，也不下载页面、字幕或视频；"
    "结果仅覆盖 MCP 当前返回且账号接口仍可见的记录，可能存在同步延迟、缺失或进度误差；"
    "时长与完成率均为元数据估算，不代表精确观看行为。"
)
DATA_SOURCE_ACCOUNT = "account_mcp"
DATA_SOURCE_BROWSER = "browser_history"
DATA_SOURCE_BROWSER_FALLBACK = "browser_history_fallback"
TITLE_SUFFIXES = ("_哔哩哔哩_bilibili", "-哔哩哔哩_bilibili", "_哔哩哔哩", "-哔哩哔哩")
MCP_PROTOCOL_VERSION = "2025-06-18"
MAX_MCP_RESPONSE_BYTES = 8 * 1024 * 1024
MAX_ACCOUNT_RECORDS = 2_000
MAX_SYNC_STATUS_BYTES = 64 * 1024
ALLOWED_SYNC_STATUSES = frozenset({"success", "risk_stop", "error"})
EDITORIAL_TOPIC_KEYWORDS = {
    "国际局势": (
        "国际",
        "外交",
        "地缘",
        "美国",
        "日本",
        "俄罗斯",
        "俄乌",
        "中美",
        "战争",
        "大选",
        "选举",
        "关税",
    ),
    "宏观经济": (
        "经济",
        "财经",
        "金融",
        "股市",
        "楼市",
        "房价",
        "消费",
        "就业",
        "贸易",
        "通胀",
        "降息",
    ),
    "科技产业": (
        "科技",
        "人工智能",
        "ai",
        "芯片",
        "互联网",
        "新能源",
        "电动车",
        "机器人",
        "产业",
    ),
    "社会观察": (
        "社会",
        "教育",
        "医疗",
        "人口",
        "年轻人",
        "职场",
        "舆论",
        "生活成本",
    ),
    "政策解读": ("政策", "法规", "改革", "治理", "监管", "税制"),
    "历史文化": ("历史", "文化", "文明", "考古"),
}
IGNORED_INTEREST_LABELS = frozenset({"待定", "未分类", "其他", "未知", "unknown", "综合"})
COMMENTARY_TITLE_MARKERS = (
    "时事",
    "时评",
    "新闻",
    "评论",
    "观察",
    "预测",
    "趋势",
    "影响",
    "争议",
    "政策",
    "法规",
    "改革",
    "监管",
    "关税",
    "降息",
    "就业",
    "年轻人",
    "产业",
    "竞争",
)
COMMENTARY_CATEGORIES = frozenset({"资讯", "财经", "新闻", "时政"})
VISUAL_TEMPLATE = "bilibili_daily_v1"


class McpClientError(RuntimeError):
    """A sanitized failure from the local read-only MCP adapter."""


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


class ReadOnlyMcpClient:
    """Minimal Streamable HTTP MCP client pinned to one read-only tool contract."""

    def __init__(self, url: str, token: str, timeout_seconds: float = 5.0):
        self.url = validate_loopback_mcp_url(url)
        self.token = token
        self.timeout_seconds = max(0.5, float(timeout_seconds))
        self.session_id: str | None = None
        self.next_id = 1
        self.opener = build_opener(_NoRedirect())

    def __enter__(self) -> "ReadOnlyMcpClient":
        request_id = self._new_id()
        response, session_id = self._post(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "method": "initialize",
                "params": {
                    "protocolVersion": MCP_PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": {"name": "meguri-bilibili-daily", "version": "1"},
                },
            }
        )
        self._require_result(response, request_id)
        self.session_id = session_id
        self._post(
            {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
            allow_empty=True,
        )
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:  # noqa: ANN001
        if not self.session_id:
            return
        request = Request(self.url, method="DELETE", headers=self._headers())
        try:
            self.opener.open(request, timeout=self.timeout_seconds).close()
        except (HTTPError, URLError, OSError):
            pass

    def call_query_history(self, target_date: date, page: int, size: int) -> dict:
        request_id = self._new_id()
        response, _ = self._post(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "method": "tools/call",
                "params": {
                    "name": "query_history_records",
                    "arguments": {
                        "page": page,
                        "size": size,
                        "sort_order": 1,
                        "date_range": f"{target_date:%Y%m%d}-{target_date:%Y%m%d}",
                        "use_local_images": False,
                    },
                },
            }
        )
        result = self._require_result(response, request_id)
        if result.get("isError") is True:
            raise McpClientError("MCP 的只读历史查询返回错误。")
        structured = result.get("structuredContent")
        if isinstance(structured, dict):
            return structured
        for item in result.get("content") or []:
            if not isinstance(item, dict) or item.get("type") != "text":
                continue
            try:
                parsed = json.loads(str(item.get("text") or ""))
            except json.JSONDecodeError:
                continue
            if isinstance(parsed, dict):
                return parsed
        raise McpClientError("MCP 历史查询没有返回可识别的结构化数据。")

    def _new_id(self) -> int:
        value = self.next_id
        self.next_id += 1
        return value

    def _headers(self) -> dict[str, str]:
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/json, text/event-stream",
            "Content-Type": "application/json; charset=utf-8",
            "MCP-Protocol-Version": MCP_PROTOCOL_VERSION,
        }
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        return headers

    def _post(self, payload: dict, allow_empty: bool = False) -> tuple[dict, str | None]:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request = Request(self.url, data=body, method="POST", headers=self._headers())
        try:
            with self.opener.open(request, timeout=self.timeout_seconds) as response:
                length = response.headers.get("Content-Length")
                if length and int(length) > MAX_MCP_RESPONSE_BYTES:
                    raise McpClientError("MCP 响应超过安全大小限制。")
                raw = response.read(MAX_MCP_RESPONSE_BYTES + 1)
                if len(raw) > MAX_MCP_RESPONSE_BYTES:
                    raise McpClientError("MCP 响应超过安全大小限制。")
                session_id = response.headers.get("Mcp-Session-Id")
                content_type = response.headers.get("Content-Type", "")
        except HTTPError as error:
            raise McpClientError(f"MCP HTTP 请求失败（{error.code}）。") from None
        except (URLError, OSError, ValueError):
            raise McpClientError("无法连接本机 BilibiliHistoryFetcher MCP。") from None
        if not raw:
            if allow_empty:
                return {}, session_id
            raise McpClientError("MCP 返回了空响应。")
        return _decode_mcp_response(raw, content_type), session_id

    @staticmethod
    def _require_result(response: dict, request_id: int) -> dict:
        if response.get("id") != request_id:
            raise McpClientError("MCP 响应编号不匹配。")
        if response.get("error") is not None:
            raise McpClientError("MCP 返回 JSON-RPC 错误。")
        result = response.get("result")
        if not isinstance(result, dict):
            raise McpClientError("MCP 响应缺少结果对象。")
        return result


def validate_loopback_mcp_url(raw_url: str) -> str:
    try:
        parsed = urlsplit(raw_url)
    except ValueError:
        raise McpClientError("MCP URL 无效。") from None
    if (
        parsed.scheme not in {"http", "https"}
        or (parsed.hostname or "").lower() not in {"127.0.0.1", "localhost", "::1"}
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise McpClientError("MCP 只允许不含凭据、查询参数或片段的本机回环地址。")
    return raw_url


def read_mcp_token(token_file: Path, project_root: Path) -> str:
    resolved = token_file.expanduser().resolve()
    root = project_root.resolve()
    if resolved == root or root in resolved.parents:
        raise McpClientError("MCP token 文件必须位于项目仓库之外。")
    if not resolved.is_file():
        raise McpClientError("未找到仓库外的 MCP token 文件。")
    token = resolved.read_text(encoding="utf-8").strip()
    if not token or len(token) > 512 or any(character.isspace() for character in token):
        raise McpClientError("MCP token 文件内容无效。")
    return token


def read_sync_status(status_file: Path | None, target_date: date) -> tuple[str, str]:
    if status_file is None:
        return "not_checked", "未配置本次同步状态文件；MCP 可读不代表今天同步成功。"
    resolved = status_file.expanduser().resolve()
    if not resolved.is_file():
        return "stale", "同步状态文件不存在，不能确认目标日期的同步结果。"
    try:
        if resolved.stat().st_size > MAX_SYNC_STATUS_BYTES:
            return "stale", "同步状态文件超过安全大小限制。"
        payload = json.loads(resolved.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "stale", "同步状态文件不可读或不是有效 JSON。"
    if not isinstance(payload, dict):
        return "stale", "同步状态文件结构无效。"
    if payload.get("task") not in {None, "bilibili_watch_history_sync"}:
        return "stale", "同步状态文件不属于 Bilibili 观看历史同步任务。"
    state_date = str(payload.get("target_date") or "")
    state = str(payload.get("status") or payload.get("sync_status") or "").strip().lower()
    if state_date != target_date.isoformat():
        return "stale", f"同步状态不属于目标日期 {target_date.isoformat()}。"
    if state not in ALLOWED_SYNC_STATUSES:
        return "stale", "目标日期的同步状态值无效。"
    notes = {
        "success": "目标日期同步任务报告成功。",
        "risk_stop": "目标日期同步因账号风控信号停止；当前元数据可能是旧快照。",
        "error": "目标日期同步任务失败；当前元数据可能是旧快照。",
    }
    return state, notes[state]


def _decode_mcp_response(raw: bytes, content_type: str) -> dict:
    text = raw.decode("utf-8")
    candidates: list[str]
    if "text/event-stream" in content_type.lower():
        candidates = [line[5:].strip() for line in text.splitlines() if line.startswith("data:")]
    else:
        candidates = [text]
    for candidate in reversed(candidates):
        try:
            parsed = json.loads(candidate)
        except json.JSONDecodeError:
            continue
        if isinstance(parsed, dict):
            return parsed
    raise McpClientError("MCP 返回了无效 JSON。")


def chromium_timestamp(instant: datetime) -> int:
    """Convert an aware datetime to Chromium microseconds since 1601 UTC."""
    if instant.tzinfo is None:
        raise ValueError("instant must be timezone-aware")
    delta = instant.astimezone(timezone.utc) - CHROMIUM_EPOCH
    return int(delta.total_seconds() * 1_000_000)


def chromium_datetime(value: int, target_zone: ZoneInfo) -> datetime:
    return (CHROMIUM_EPOCH + timedelta(microseconds=int(value))).astimezone(target_zone)


def day_bounds(target_date: date, target_zone: ZoneInfo) -> tuple[int, int]:
    start = datetime.combine(target_date, time.min, target_zone)
    return chromium_timestamp(start), chromium_timestamp(start + timedelta(days=1))


def clean_title(raw_title: object, bvid: str) -> str:
    title = " ".join(str(raw_title or "").replace("\x00", "").split())
    for suffix in TITLE_SUFFIXES:
        if title.endswith(suffix):
            title = title[: -len(suffix)].rstrip()
            break
    return title or bvid


def extract_bvid(raw_url: object) -> str | None:
    try:
        parsed = urlsplit(str(raw_url or ""))
    except ValueError:
        return None
    if (parsed.hostname or "").lower().rstrip(".") not in ALLOWED_BILIBILI_HOSTS:
        return None
    match = BVID_PATH.match(parsed.path)
    if not match:
        return None
    value = match.group(1)
    return "BV" + value[2:]


def normalize_bvid(raw_bvid: object) -> str | None:
    value = str(raw_bvid or "").strip()
    if not BVID_VALUE.fullmatch(value):
        return None
    return "BV" + value[2:]


def _query_database(history_file: Path, start: int, end: int) -> list[tuple[str, str, int]]:
    uri = history_file.resolve().as_uri() + "?mode=ro"
    with closing(sqlite3.connect(uri, uri=True, timeout=2.0)) as connection:
        connection.execute("PRAGMA query_only = ON")
        rows = connection.execute(
            """
            SELECT urls.url, urls.title, visits.visit_time
              FROM visits
              JOIN urls ON urls.id = visits.url
             WHERE visits.visit_time >= ?
               AND visits.visit_time < ?
               AND (urls.url LIKE '%://www.bilibili.com/video/%'
                    OR urls.url LIKE '%://m.bilibili.com/video/%')
             ORDER BY visits.visit_time ASC
            """,
            (start, end),
        ).fetchall()
    return [(str(url), str(title or ""), int(visit_time)) for url, title, visit_time in rows]


def _snapshot_query(history_file: Path, start: int, end: int) -> list[tuple[str, str, int]]:
    """Retry a locked live database from a copied snapshot without mutating it."""
    with tempfile.TemporaryDirectory(prefix="meguri-browser-history-") as temporary:
        snapshot = Path(temporary) / "History"
        shutil.copy2(history_file, snapshot)
        for suffix in ("-wal", "-shm"):
            sidecar = history_file.with_name(history_file.name + suffix)
            if sidecar.is_file():
                shutil.copy2(sidecar, snapshot.with_name(snapshot.name + suffix))
        return _query_database(snapshot, start, end)


def read_history(history_file: Path, start: int, end: int) -> list[tuple[str, str, int]]:
    if not history_file.is_file():
        raise FileNotFoundError("History 文件不存在")
    try:
        return _query_database(history_file, start, end)
    except sqlite3.OperationalError as error:
        lowered = str(error).lower()
        if "locked" not in lowered and "busy" not in lowered:
            raise
        return _snapshot_query(history_file, start, end)


def discover_histories(local_app_data: Path | None = None) -> list[tuple[str, Path]]:
    if local_app_data is None:
        raw_local_root = os.environ.get("LOCALAPPDATA")
        if not raw_local_root:
            return []
        local_root = Path(raw_local_root)
    else:
        local_root = local_app_data
    browser_roots = (
        ("Chrome", local_root / "Google" / "Chrome" / "User Data"),
        ("Edge", local_root / "Microsoft" / "Edge" / "User Data"),
    )
    discovered: list[tuple[str, Path]] = []
    for browser, user_data in browser_roots:
        if not user_data.is_dir():
            continue
        profiles = [child for child in user_data.iterdir() if child.is_dir()]
        profiles.sort(key=lambda item: (item.name != "Default", item.name.casefold()))
        for profile in profiles:
            if profile.name != "Default" and not profile.name.startswith("Profile "):
                continue
            history = profile / "History"
            if history.is_file():
                discovered.append((f"{browser}/{profile.name}", history))
    return discovered


def parse_history_arguments(values: Sequence[str]) -> list[tuple[str, Path]]:
    sources: list[tuple[str, Path]] = []
    for index, value in enumerate(values, start=1):
        if "=" in value:
            name, raw_path = value.split("=", 1)
            name = name.strip() or f"History {index}"
        else:
            name, raw_path = f"History {index}", value
        sources.append((name, Path(raw_path).expanduser()))
    return sources


def _extract_history_page(payload: dict) -> tuple[list[dict], int]:
    current: object = payload
    for _ in range(5):
        if not isinstance(current, dict):
            break
        if current.get("status") == "error":
            raise McpClientError("BilibiliHistoryFetcher MCP 历史查询失败。")
        records = current.get("records")
        if isinstance(records, list):
            safe_records = [item for item in records if isinstance(item, dict)]
            try:
                total = max(0, int(current.get("total", len(safe_records))))
            except (TypeError, ValueError):
                total = len(safe_records)
            return safe_records, total
        if isinstance(current.get("data"), dict):
            current = current["data"]
            continue
        if isinstance(current.get("result"), dict):
            current = current["result"]
            continue
        break
    raise McpClientError("MCP 历史查询响应与已固定的只读适配契约不兼容。")


def fetch_account_records(
    target_date: date,
    mcp_url: str,
    token_file: Path,
    project_root: Path,
    timeout_seconds: float,
) -> tuple[list[dict], int, bool]:
    token = read_mcp_token(token_file, project_root)
    records: list[dict] = []
    total = 0
    page = 1
    page_size = 100
    with ReadOnlyMcpClient(mcp_url, token, timeout_seconds=timeout_seconds) as client:
        while len(records) < MAX_ACCOUNT_RECORDS:
            payload = client.call_query_history(target_date, page=page, size=page_size)
            batch, total = _extract_history_page(payload)
            records.extend(batch)
            if not batch or len(records) >= total:
                break
            page += 1
    truncated = total > len(records)
    return records[:MAX_ACCOUNT_RECORDS], total, truncated


def _integer(value: object, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError, OverflowError):
        return default


def _normalized_progress(progress: object, duration: object) -> tuple[int, int, bool]:
    duration_seconds = max(0, _integer(duration))
    raw_progress = _integer(progress)
    if raw_progress == -1:
        progress_seconds = duration_seconds
    else:
        progress_seconds = max(0, raw_progress)
        if duration_seconds:
            progress_seconds = min(progress_seconds, duration_seconds)
    completed = duration_seconds > 0 and progress_seconds >= duration_seconds * 0.9
    return progress_seconds, duration_seconds, completed


def _iso_unix(value: int | None, target_zone: ZoneInfo) -> str | None:
    if value is None or value <= 0:
        return None
    return datetime.fromtimestamp(value, target_zone).isoformat(timespec="seconds")


def build_account_report(
    target_date: date,
    target_zone: ZoneInfo,
    records: Sequence[dict],
    reported_total: int,
    truncated: bool,
    project_root: Path,
    sync_status: str,
    sync_note: str,
) -> dict:
    grouped: dict[str, dict] = {}
    author_counts: dict[str, int] = {}
    category_counts: dict[str, int] = {}
    estimated_watch_seconds = 0
    completed_records = 0
    accepted_records = 0

    for record in records:
        bvid = normalize_bvid(record.get("bvid"))
        view_at = _integer(record.get("view_at"))
        if not bvid or view_at <= 0:
            continue
        accepted_records += 1
        title = clean_title(record.get("title"), bvid)
        author = " ".join(str(record.get("author_name") or "未知 UP 主").split())
        category = " ".join(
            str(record.get("main_category") or record.get("tag_name") or "未分类").split()
        )
        progress_seconds, duration_seconds, completed = _normalized_progress(
            record.get("progress"), record.get("duration")
        )
        estimated_watch_seconds += progress_seconds
        completed_records += int(completed)
        author_counts[author] = author_counts.get(author, 0) + 1
        category_counts[category] = category_counts.get(category, 0) + 1
        item = grouped.setdefault(
            bvid,
            {
                "bvid": bvid,
                "title": title,
                "url": f"https://www.bilibili.com/video/{bvid}/",
                "visit_count": 0,
                "_first_time": view_at,
                "_last_time": view_at,
                "_latest_time": view_at,
                "author_name": author,
                "author_mid": _integer(record.get("author_mid")),
                "tag_name": " ".join(str(record.get("tag_name") or "").split()) or None,
                "main_category": " ".join(str(record.get("main_category") or "").split()) or None,
                "business": " ".join(str(record.get("business") or "").split()) or None,
                "duration_seconds": duration_seconds,
                "progress_seconds": progress_seconds,
                "completion_rate": round(progress_seconds / duration_seconds, 4) if duration_seconds else None,
                "estimated_watch_seconds": 0,
            },
        )
        item["visit_count"] += 1
        item["estimated_watch_seconds"] += progress_seconds
        item["_first_time"] = min(item["_first_time"], view_at)
        item["_last_time"] = max(item["_last_time"], view_at)
        if view_at >= item["_latest_time"]:
            item.update(
                {
                    "title": title,
                    "author_name": author,
                    "author_mid": _integer(record.get("author_mid")),
                    "tag_name": " ".join(str(record.get("tag_name") or "").split()) or None,
                    "main_category": " ".join(str(record.get("main_category") or "").split()) or None,
                    "business": " ".join(str(record.get("business") or "").split()) or None,
                    "duration_seconds": duration_seconds,
                    "progress_seconds": progress_seconds,
                    "completion_rate": round(progress_seconds / duration_seconds, 4) if duration_seconds else None,
                    "_latest_time": view_at,
                }
            )

    videos_internal = sorted(grouped.values(), key=lambda item: (item["_last_time"], item["bvid"]), reverse=True)
    first_time = min((item["_first_time"] for item in videos_internal), default=None)
    last_time = max((item["_last_time"] for item in videos_internal), default=None)
    videos = [
        {
            "bvid": item["bvid"],
            "title": item["title"],
            "url": item["url"],
            "visit_count": item["visit_count"],
            "first_visited_at": _iso_unix(item["_first_time"], target_zone),
            "last_visited_at": _iso_unix(item["_last_time"], target_zone),
            "sources": ["BilibiliHistoryFetcher/MCP"],
            "author_name": item["author_name"],
            "author_mid": item["author_mid"],
            "tag_name": item["tag_name"],
            "main_category": item["main_category"],
            "business": item["business"],
            "duration_seconds": item["duration_seconds"],
            "progress_seconds": item["progress_seconds"],
            "completion_rate": item["completion_rate"],
            "estimated_watch_seconds": item["estimated_watch_seconds"],
            "content_summary_status": "not_requested",
        }
        for item in videos_internal
    ]
    top_author = max(author_counts.items(), key=lambda item: (item[1], item[0])) if author_counts else None
    top_category = max(category_counts.items(), key=lambda item: (item[1], item[0])) if category_counts else None
    if videos:
        summary = (
            f"{target_date.isoformat()} 账号历史同步数据包含 {len(videos)} 个视频、{accepted_records} 条观看记录，"
            f"元数据估算观看 {format_duration(estimated_watch_seconds)}，完成度达到 90% 的记录 {completed_records} 条"
        )
        if top_author:
            summary += f"；出现最多的 UP 主是 {top_author[0]}（{top_author[1]} 条）"
        if top_category:
            summary += f"，最常见分区是 {top_category[0]}（{top_category[1]} 条）"
        summary += "。"
    else:
        summary = f"{target_date.isoformat()} 的 Bilibili 账号同步数据中没有可报告的 BV 视频记录。"

    source_message_parts = ["已通过只读 MCP 获取账号观看历史元数据", sync_note]
    if reported_total != accepted_records:
        source_message_parts.append(f"MCP 报告 {reported_total} 条，纳入 {accepted_records} 条有效 BV 记录")
    if truncated:
        source_message_parts.append(f"超过安全上限，仅纳入前 {MAX_ACCOUNT_RECORDS} 条")
    report_file = project_root.resolve() / "reports" / "daily" / f"bilibili-{target_date.isoformat()}.md"
    report = {
        "status": "ready" if videos else "empty",
        "date": target_date.isoformat(),
        "generated_at": datetime.now(target_zone).isoformat(timespec="seconds"),
        "data_source": DATA_SOURCE_ACCOUNT,
        "sync_status": sync_status,
        "unique_videos": len(videos),
        "total_visits": accepted_records,
        "first_visited_at": _iso_unix(first_time, target_zone),
        "last_visited_at": _iso_unix(last_time, target_zone),
        "summary": summary,
        "videos": videos,
        "sources": [
            {
                "name": "BilibiliHistoryFetcher/MCP",
                "kind": DATA_SOURCE_ACCOUNT,
                "status": "ready",
                "visit_count": accepted_records,
                "message": "；".join(source_message_parts),
            }
        ],
        "boundary": ACCOUNT_BOUNDARY,
        "artifacts": [
            {
                "label": "Bilibili 账号观看日报（Markdown）",
                "href": f"/v1/daily/bilibili/report?date={target_date.isoformat()}",
                "local_path": str(report_file),
            }
        ],
        "error": None,
    }
    report["visual_payload"] = build_visual_payload(report)
    return report


def format_duration(seconds: int) -> str:
    normalized = max(0, int(seconds))
    hours, remainder = divmod(normalized, 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours} 小时 {minutes} 分钟"
    if minutes:
        return f"{minutes} 分钟 {secs} 秒"
    return f"{secs} 秒"


def format_duration_compact(seconds: int) -> str:
    normalized = max(0, int(seconds))
    hours, remainder = divmod(normalized, 3600)
    minutes = remainder // 60
    if hours:
        return f"{hours}小时{minutes}分"
    if minutes:
        return f"{minutes}分钟"
    return f"{normalized}秒"


def _clean_visual_text(value: object, maximum: int = 80) -> str:
    return " ".join(str(value or "").replace("\x00", "").split())[:maximum]


def _topics_in_text(value: object) -> list[str]:
    haystack = _clean_visual_text(value, 240).casefold()
    return [
        topic
        for topic, keywords in EDITORIAL_TOPIC_KEYWORDS.items()
        if any(keyword.casefold() in haystack for keyword in keywords)
    ]


def _commentary_topics(video: dict) -> list[str]:
    title = _clean_visual_text(video.get("title"), 160)
    topics = _topics_in_text(title)
    category = _clean_visual_text(video.get("main_category"), 24)
    if not topics:
        return []
    if (
        len(topics) >= 2
        or category in COMMENTARY_CATEGORIES
        or any(marker in title for marker in COMMENTARY_TITLE_MARKERS)
    ):
        return topics
    return []


def _visual_weight(video: dict) -> int:
    watch_seconds = _integer(video.get("estimated_watch_seconds"))
    if watch_seconds > 0:
        return watch_seconds
    progress_seconds = _integer(video.get("progress_seconds"))
    if progress_seconds > 0:
        return progress_seconds
    return max(1, _integer(video.get("visit_count"))) * 60


def _ranked_labels(videos: Sequence[dict]) -> tuple[list[str], list[str]]:
    interest_weights: dict[str, int] = {}
    topic_weights: dict[str, int] = {}
    for video in videos:
        weight = _visual_weight(video)
        labels = []
        for field in ("tag_name", "main_category"):
            label = _clean_visual_text(video.get(field), 12)
            if label and label.casefold() not in IGNORED_INTEREST_LABELS and label not in labels:
                labels.append(label)
        topics = _commentary_topics(video)
        labels.extend(topic for topic in topics if topic not in labels)
        for label in labels:
            interest_weights[label] = interest_weights.get(label, 0) + weight
        for topic in topics:
            topic_weights[topic] = topic_weights.get(topic, 0) + weight
    ranked_interests = sorted(interest_weights, key=lambda value: (-interest_weights[value], value))
    ranked_topics = sorted(topic_weights, key=lambda value: (-topic_weights[value], value))
    return ranked_interests[:6], ranked_topics


def _top_commentary(videos: Sequence[dict]) -> list[dict]:
    candidates: list[tuple[float, dict]] = []
    for video in videos:
        topics = _commentary_topics(video)
        if not topics:
            continue
        rate = video.get("completion_rate")
        completion_rate = float(rate) if isinstance(rate, (int, float)) else None
        score = len(topics) * 5 + min(_visual_weight(video) / 900, 4)
        if completion_rate is not None:
            score += completion_rate * 3
        candidates.append(
            (
                score,
                {
                    "title": _clean_visual_text(video.get("title"), 80),
                    "author_name": _clean_visual_text(video.get("author_name"), 24) or None,
                    "topic": topics[0],
                    "completion_rate": round(completion_rate, 4) if completion_rate is not None else None,
                    "visit_count": max(0, _integer(video.get("visit_count"))),
                },
            )
        )
    candidates.sort(key=lambda item: (-item[0], item[1]["title"]))
    return [item for _, item in candidates[:3]]


def build_visual_payload(report: dict) -> dict:
    videos = [item for item in (report.get("videos") or []) if isinstance(item, dict)]
    account_source = report.get("data_source") == DATA_SOURCE_ACCOUNT
    rates = [
        float(item["completion_rate"])
        for item in videos
        if isinstance(item.get("completion_rate"), (int, float))
    ]
    estimated_watch_seconds = (
        sum(max(0, _integer(item.get("estimated_watch_seconds"))) for item in videos)
        if account_source
        else None
    )
    average_completion_rate = round(sum(rates) / len(rates), 4) if rates else None
    completed_videos = sum(rate >= 0.9 for rate in rates)
    interest_tags, editorial_topics = _ranked_labels(videos)
    primary_topics = editorial_topics[:2] or interest_tags[:2]
    top_commentary = _top_commentary(videos)

    if account_source:
        overview = (
            f"昨日共观看 {len(videos)} 个视频，估算时长{format_duration_compact(estimated_watch_seconds or 0)}"
        )
        if average_completion_rate is not None:
            overview += f"，平均播放进度约 {average_completion_rate:.0%}"
        overview += "。"
    else:
        overview = (
            f"昨日浏览器记录到 {len(videos)} 个 B站视频页面；当前数据源无法估算观看时长与播放进度。"
        )
    preference = (
        f"兴趣主要集中在{'、'.join(interest_tags[:3])}。"
        if interest_tags
        else "暂未形成稳定的兴趣标签。"
    )
    if editorial_topics:
        observation = f"从标题与分区推测，时评内容更多涉及{'、'.join(editorial_topics[:3])}。"
    else:
        observation = "昨日未从标题与分区识别出明确的时评内容。"
    overall_summary = overview + preference + observation + "以上仅为元数据观察，未读取字幕或视频正文。"

    source_label = {
        DATA_SOURCE_ACCOUNT: "B站账号历史（时长为估算）",
        DATA_SOURCE_BROWSER_FALLBACK: "浏览器历史降级（仅访问记录）",
        DATA_SOURCE_BROWSER: "浏览器历史（仅访问记录）",
    }.get(str(report.get("data_source") or ""), "本地元数据")
    return {
        "schema_version": 1,
        "template": VISUAL_TEMPLATE,
        "date": str(report.get("date") or ""),
        "data_source": str(report.get("data_source") or ""),
        "source_label": source_label,
        "statistics": {
            "video_count": len(videos),
            "record_count": max(0, _integer(report.get("total_visits"))),
            "estimated_watch_seconds": estimated_watch_seconds,
            "average_completion_rate": average_completion_rate,
            "completed_videos": completed_videos if rates else None,
            "primary_topics": primary_topics,
        },
        "top_commentary": top_commentary,
        "interest_tags": interest_tags,
        "overall_summary": overall_summary[:360],
        "analysis_basis": "metadata_only",
    }


def _safe_error(error: Exception) -> str:
    text = " ".join(str(error).split())[:160]
    if isinstance(error, FileNotFoundError):
        return text or "History 文件不存在"
    if isinstance(error, sqlite3.Error):
        return "SQLite History 无法读取" + (f"：{text}" if text else "")
    return error.__class__.__name__ + (f"：{text}" if text else "")


def _iso(visit_time: int | None, target_zone: ZoneInfo) -> str | None:
    if visit_time is None:
        return None
    return chromium_datetime(visit_time, target_zone).isoformat(timespec="seconds")


def _summary(target_date: date, videos: list[dict], total_visits: int, target_zone: ZoneInfo) -> str:
    if not videos:
        return f"{target_date.isoformat()} 未在可读取的本机 Chrome/Edge History 中发现 Bilibili 视频页面访问。"
    first = min(item["_first_time"] for item in videos)
    last = max(item["_last_time"] for item in videos)
    most_frequent = max(videos, key=lambda item: (item["visit_count"], item["_last_time"]))
    return (
        f"{target_date.isoformat()} 本机浏览器访问了 {len(videos)} 个 Bilibili 视频页面，"
        f"累计页面访问 {total_visits} 次，时间范围 "
        f"{chromium_datetime(first, target_zone):%H:%M}–{chromium_datetime(last, target_zone):%H:%M}；"
        f"访问次数最多的是《{most_frequent['title']}》（{most_frequent['visit_count']} 次）。"
    )


def build_browser_report(
    target_date: date,
    target_zone: ZoneInfo,
    histories: Iterable[tuple[str, Path]],
    project_root: Path,
    account_failure: str | None = None,
    sync_status: str = "not_checked",
    sync_note: str = "未配置本次同步状态文件。",
) -> dict:
    start, end = day_bounds(target_date, target_zone)
    grouped: dict[str, dict] = {}
    source_results: list[dict] = []
    ready_sources = 0

    for source_name, history_file in histories:
        try:
            rows = read_history(history_file, start, end)
            ready_sources += 1
            accepted = 0
            for raw_url, raw_title, visit_time in rows:
                bvid = extract_bvid(raw_url)
                if not bvid:
                    continue
                accepted += 1
                title = clean_title(raw_title, bvid)
                item = grouped.setdefault(
                    bvid,
                    {
                        "bvid": bvid,
                        "title": title,
                        "url": f"https://www.bilibili.com/video/{bvid}/",
                        "visit_count": 0,
                        "_first_time": visit_time,
                        "_last_time": visit_time,
                        "_title_time": visit_time,
                        "_sources": set(),
                    },
                )
                item["visit_count"] += 1
                item["_first_time"] = min(item["_first_time"], visit_time)
                item["_last_time"] = max(item["_last_time"], visit_time)
                item["_sources"].add(source_name)
                if visit_time >= item["_title_time"] and title != bvid:
                    item["title"] = title
                    item["_title_time"] = visit_time
            source_results.append(
                {
                    "name": source_name,
                    "kind": DATA_SOURCE_BROWSER,
                    "status": "ready",
                    "visit_count": accepted,
                }
            )
        except (OSError, sqlite3.Error, ValueError) as error:
            source_results.append(
                {
                    "name": source_name,
                    "kind": DATA_SOURCE_BROWSER,
                    "status": "unavailable",
                    "visit_count": 0,
                    "message": _safe_error(error),
                }
            )

    data_source = DATA_SOURCE_BROWSER_FALLBACK if account_failure else DATA_SOURCE_BROWSER
    if account_failure:
        source_results.insert(
            0,
            {
                "name": "BilibiliHistoryFetcher/MCP",
                "kind": DATA_SOURCE_ACCOUNT,
                "status": "unavailable",
                "visit_count": 0,
                    "message": f"{account_failure} {sync_note}",
            },
        )

    videos_internal = sorted(grouped.values(), key=lambda item: (item["_last_time"], item["bvid"]), reverse=True)
    total_visits = sum(item["visit_count"] for item in videos_internal)
    first_time = min((item["_first_time"] for item in videos_internal), default=None)
    last_time = max((item["_last_time"] for item in videos_internal), default=None)
    videos = [
        {
            "bvid": item["bvid"],
            "title": item["title"],
            "url": item["url"],
            "visit_count": item["visit_count"],
            "first_visited_at": _iso(item["_first_time"], target_zone),
            "last_visited_at": _iso(item["_last_time"], target_zone),
            "sources": sorted(item["_sources"]),
        }
        for item in videos_internal
    ]

    if ready_sources == 0:
        status = "unavailable"
        error = "未找到或未能读取任何 Chrome/Edge History 数据库。"
    elif not videos:
        status = "empty"
        error = None
    else:
        status = "ready"
        error = None

    report_file = project_root.resolve() / "reports" / "daily" / f"bilibili-{target_date.isoformat()}.md"
    report = {
        "status": status,
        "date": target_date.isoformat(),
        "generated_at": datetime.now(target_zone).isoformat(timespec="seconds"),
        "data_source": data_source,
        "sync_status": sync_status,
        "unique_videos": len(videos),
        "total_visits": total_visits,
        "first_visited_at": _iso(first_time, target_zone),
        "last_visited_at": _iso(last_time, target_zone),
        "summary": _summary(target_date, videos_internal, total_visits, target_zone),
        "videos": videos,
        "sources": source_results,
        "boundary": BROWSER_BOUNDARY
        + (" 账号只读 MCP 不可用，本次已明确降级为浏览器页面访问记录。" if account_failure else ""),
        "artifacts": [
            {
                "label": (
                    "Bilibili 浏览器降级日报（Markdown）"
                    if account_failure
                    else "Bilibili 浏览器访问日报（Markdown）"
                ),
                "href": f"/v1/daily/bilibili/report?date={target_date.isoformat()}",
                "local_path": str(report_file),
            }
        ],
        "error": error,
    }
    report["visual_payload"] = build_visual_payload(report)
    return report


def build_report(
    target_date: date,
    target_zone: ZoneInfo,
    histories: Iterable[tuple[str, Path]],
    project_root: Path,
    mcp_url: str | None = None,
    mcp_token_file: Path | None = None,
    mcp_timeout_seconds: float = 5.0,
    sync_status_file: Path | None = None,
) -> dict:
    sync_status, sync_note = read_sync_status(sync_status_file, target_date)
    account_failure: str | None = None
    if mcp_url or mcp_token_file:
        if not mcp_url or mcp_token_file is None:
            account_failure = "账号 MCP 配置不完整。"
        else:
            try:
                records, reported_total, truncated = fetch_account_records(
                    target_date,
                    mcp_url,
                    mcp_token_file,
                    project_root,
                    mcp_timeout_seconds,
                )
                return build_account_report(
                    target_date,
                    target_zone,
                    records,
                    reported_total,
                    truncated,
                    project_root,
                    sync_status,
                    sync_note,
                )
            except McpClientError as error:
                account_failure = str(error)
    return build_browser_report(
        target_date,
        target_zone,
        histories,
        project_root,
        account_failure=account_failure,
        sync_status=sync_status,
        sync_note=sync_note,
    )


def markdown_escape(value: object) -> str:
    return str(value or "").replace("\\", "\\\\").replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def write_markdown(report: dict, project_root: Path) -> Path:
    target = project_root.resolve() / "reports" / "daily" / f"bilibili-{report['date']}.md"
    target.parent.mkdir(parents=True, exist_ok=True)
    data_source = report.get("data_source") or DATA_SOURCE_BROWSER
    title = (
        f"# {report['date']} Bilibili 账号观看日报"
        if data_source == DATA_SOURCE_ACCOUNT
        else f"# {report['date']} Bilibili 本机浏览器访问日报"
    )
    source_label = {
        DATA_SOURCE_ACCOUNT: "账号历史（BilibiliHistoryFetcher 只读 MCP）",
        DATA_SOURCE_BROWSER_FALLBACK: "浏览器 History（账号 MCP 不可用后的显式降级）",
        DATA_SOURCE_BROWSER: "浏览器 History",
    }.get(data_source, data_source)
    lines = [
        title,
        "",
        f"> 数据源：{source_label}",
        f"> 同步状态：{report.get('sync_status') or 'unknown'}",
        f"> 数据边界：{report['boundary']}",
        "> 本日报只做元数据分析；所有视频的内容总结状态默认为 `not_requested`，"
        "只有用户明确选择 BV 号后才可进入后续内容总结接口。",
        "",
        "## 快速总结",
        "",
        str(report["summary"]),
        "",
        "## 统计",
        "",
        f"- 独立 BV 视频：{report['unique_videos']}",
        f"- 观看/访问记录：{report['total_visits']}",
        f"- 首次访问：{report['first_visited_at'] or '无'}",
        f"- 末次访问：{report['last_visited_at'] or '无'}",
        "",
        "## 视频元数据",
        "",
    ]
    if report["videos"]:
        if data_source == DATA_SOURCE_ACCOUNT:
            lines.extend(
                [
                    "| 首次 | 末次 | 次数 | UP 主 | 分区 | 进度/时长 | 视频 |",
                    "| --- | --- | ---: | --- | --- | ---: | --- |",
                ]
            )
        else:
            lines.extend(["| 首次访问 | 末次访问 | 次数 | 页面 |", "| --- | --- | ---: | --- |"])
        for item in report["videos"]:
            if data_source == DATA_SOURCE_ACCOUNT:
                progress = format_duration(_integer(item.get("progress_seconds")))
                duration = format_duration(_integer(item.get("duration_seconds")))
                lines.append(
                    "| {first} | {last} | {count} | {author} | {category} | {progress}/{duration} | "
                    "[{title}]({url}) |".format(
                        first=markdown_escape(item["first_visited_at"]),
                        last=markdown_escape(item["last_visited_at"]),
                        count=item["visit_count"],
                        author=markdown_escape(item.get("author_name") or "未知"),
                        category=markdown_escape(item.get("main_category") or item.get("tag_name") or "未分类"),
                        progress=progress,
                        duration=duration,
                        title=markdown_escape(item["title"]),
                        url=item["url"],
                    )
                )
            else:
                lines.append(
                    "| {first} | {last} | {count} | [{title}]({url}) |".format(
                        first=markdown_escape(item["first_visited_at"]),
                        last=markdown_escape(item["last_visited_at"]),
                        count=item["visit_count"],
                        title=markdown_escape(item["title"]),
                        url=item["url"],
                    )
                )
    else:
        lines.append("- 当天没有可报告的 Bilibili BV 视频页面访问。")
    lines.extend(["", "## 数据源状态", ""])
    if report["sources"]:
        for source in report["sources"]:
            detail = f"，{source['message']}" if source.get("message") else ""
            lines.append(
                f"- {markdown_escape(source['name'])}（{markdown_escape(source.get('kind') or 'unknown')}）："
                f"{source['status']}，"
                f"有效页面访问 {source['visit_count']} 次{markdown_escape(detail)}"
            )
    else:
        lines.append("- 未发现默认 Chrome/Edge Profile 的 History 文件。")
    lines.extend(["", f"生成时间：{report['generated_at']}", ""])
    target.write_text("\n".join(lines), encoding="utf-8")
    return target


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Generate a read-only, metadata-only Bilibili daily report")
    parser.add_argument("--project-root", required=True, type=Path)
    parser.add_argument("--date", dest="target_date", default=date.today().isoformat())
    parser.add_argument("--timezone", default="Asia/Shanghai")
    parser.add_argument(
        "--mcp-url",
        help="loopback BilibiliHistoryFetcher Streamable HTTP MCP URL",
    )
    parser.add_argument(
        "--mcp-token-file",
        type=Path,
        help="repository-external UTF-8 file containing only the MCP Bearer token",
    )
    parser.add_argument(
        "--mcp-timeout-seconds",
        type=float,
        default=5.0,
        help="per-request timeout for the loopback MCP adapter",
    )
    parser.add_argument(
        "--sync-status-file",
        type=Path,
        help="optional runner state JSON; only the target date and success/risk_stop/error are accepted",
    )
    parser.add_argument(
        "--history",
        action="append",
        default=[],
        metavar="NAME=PATH",
        help="explicit History SQLite source; repeatable and disables default browser discovery",
    )
    args = parser.parse_args()

    target_date = date.fromisoformat(args.target_date)
    target_zone = ZoneInfo(args.timezone)
    histories = parse_history_arguments(args.history) if args.history else discover_histories()
    report = build_report(
        target_date,
        target_zone,
        histories,
        args.project_root,
        mcp_url=args.mcp_url,
        mcp_token_file=args.mcp_token_file,
        mcp_timeout_seconds=args.mcp_timeout_seconds,
        sync_status_file=args.sync_status_file,
    )
    write_markdown(report, args.project_root)
    print(json.dumps(report, ensure_ascii=False, separators=(",", ":")))
    if report["status"] == "unavailable":
        raise SystemExit(2)


if __name__ == "__main__":
    main()
