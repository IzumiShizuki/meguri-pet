#!/usr/bin/env python3
"""Publish one local daily report to the Meguri desktop inbox and remote Core."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from datetime import date, datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


MAX_MARKDOWN_BYTES = 256 * 1024
MAX_RESPONSE_BYTES = 1024 * 1024
MAX_RENDER_PAYLOAD_BYTES = 32 * 1024
KIND_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]{0,31}$")


class PublishError(RuntimeError):
    """A bounded, non-secret report publication error."""


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


def _required_text(value: object, field: str, maximum: int) -> str:
    text = str(value or "").strip()
    if not text or len(text) > maximum:
        raise PublishError(f"report field {field} is missing or too long")
    return text


def _bounded_integer(value: object, field: str) -> int:
    if isinstance(value, bool):
        raise PublishError(f"report field {field} must be an integer")
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise PublishError(f"report field {field} must be an integer") from exc
    if parsed < 0 or parsed > 1_000_000:
        raise PublishError(f"report field {field} is out of range")
    return parsed


def _source_labels(data_source: str) -> tuple[str, str]:
    return {
        "account_mcp": ("账号只读 MCP", "アカウント読み取り専用MCP"),
        "browser_history_fallback": ("本机浏览器降级记录", "ローカルブラウザのフォールバック履歴"),
        "browser_history": ("本机浏览器记录", "ローカルブラウザ履歴"),
    }.get(data_source, ("已标记的本地元数据", "ラベル付きローカルメタデータ"))


def _render_payload(value: object) -> dict | None:
    if value is None:
        return None
    if not isinstance(value, dict):
        raise PublishError("report visual_payload must be an object")
    if value.get("schema_version") != 1 or value.get("template") != "bilibili_daily_v1":
        raise PublishError("report visual_payload has an unsupported contract")
    try:
        encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise PublishError("report visual_payload is not JSON serializable") from exc
    if len(encoded) > MAX_RENDER_PAYLOAD_BYTES:
        raise PublishError("report visual_payload is too large")
    return value


def build_envelope(kind: str, report: dict, markdown: str) -> dict:
    if not KIND_PATTERN.fullmatch(kind):
        raise PublishError("report kind is invalid")
    report_date = date.fromisoformat(_required_text(report.get("date"), "date", 10))
    status = _required_text(report.get("status"), "status", 32)
    if status not in {"ready", "empty"}:
        raise PublishError("only ready or empty reports can be published")
    markdown_bytes = markdown.encode("utf-8")
    if not markdown_bytes or len(markdown_bytes) > MAX_MARKDOWN_BYTES:
        raise PublishError("report markdown is empty or too large")

    generated_at = _required_text(report.get("generated_at"), "generated_at", 64)
    try:
        datetime.fromisoformat(generated_at.replace("Z", "+00:00"))
    except ValueError as exc:
        raise PublishError("report generated_at is invalid") from exc

    data_source = _required_text(report.get("data_source"), "data_source", 64)
    sync_status = _required_text(report.get("sync_status"), "sync_status", 64)
    summary = _required_text(report.get("summary"), "summary", 4000)
    unique_videos = _bounded_integer(report.get("unique_videos"), "unique_videos")
    total_visits = _bounded_integer(report.get("total_visits"), "total_visits")
    render_payload = _render_payload(report.get("visual_payload"))
    source_zh, source_ja = _source_labels(data_source)

    title = f"{report_date.isoformat()} Bilibili 观看元数据日报"
    delivery_text_zh = "\n".join(
        (
            f"【兄さん，{report_date.isoformat()} 的 Bilibili 观看元数据日报送到了。】",
            f"【共 {unique_videos} 个视频、{total_visits} 条观看记录；数据源：{source_zh}。】",
        )
    )
    delivery_text_ja = "\n".join(
        (
            f"【兄さん、{report_date.isoformat()}のBilibili視聴メタデータ日報が届いたよ。】",
            f"【動画は{unique_videos}本、視聴記録は{total_visits}件。データソース：{source_ja}。】",
        )
    )
    delivery_text = "\n".join(
        (
            delivery_text_zh.splitlines()[0],
            delivery_text_ja.splitlines()[0],
            delivery_text_zh.splitlines()[1],
            delivery_text_ja.splitlines()[1],
        )
    )
    digest = hashlib.sha256(markdown_bytes).hexdigest()
    return {
        "schema_version": 1,
        "report_id": f"{kind}:{report_date.isoformat()}",
        "kind": kind,
        "date": report_date.isoformat(),
        "title": title,
        "summary": summary,
        "delivery_text": delivery_text,
        "delivery_speech_text": delivery_text_zh,
        "generated_at": generated_at,
        "published_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "data_source": data_source,
        "sync_status": sync_status,
        "unique_videos": unique_videos,
        "total_visits": total_visits,
        "render_payload": render_payload,
        "markdown_sha256": digest,
        "markdown": markdown,
    }


def write_local_notice(envelope: dict, target: Path) -> None:
    notice = {key: value for key, value in envelope.items() if key != "markdown"}
    notice["markdown_url"] = f"/reports/daily/bilibili-{notice['date']}.md"
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".tmp")
    temporary.write_text(
        json.dumps(notice, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    os.replace(temporary, target)


def validate_core_url(value: str) -> str:
    url = value.rstrip("/")
    parsed = urlsplit(url)
    if parsed.scheme == "https" and parsed.hostname:
        return url
    if parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost", "::1"}:
        return url
    raise PublishError("remote Core URL must use HTTPS or loopback HTTP")


def upload(envelope: dict, core_url: str, token: str, timeout_seconds: float) -> dict:
    if not token.strip():
        raise PublishError("Core token file is empty")
    body = json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    request = Request(
        validate_core_url(core_url) + "/v1/daily/reports",
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {token.strip()}",
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
            "Idempotency-Key": envelope["report_id"],
            "X-Meguri-Tenant-ID": "meguri-staging",
            "X-Meguri-User-ID": "local-desktop-user",
            "X-Meguri-Client-ID": "desktop_report_publisher",
            "X-Meguri-Session-ID": "daily-report-upload",
        },
    )
    try:
        with build_opener(_NoRedirect()).open(request, timeout=max(1.0, timeout_seconds)) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except HTTPError as exc:
        raise PublishError(f"remote Core rejected the report with HTTP {exc.code}") from exc
    except (URLError, OSError) as exc:
        raise PublishError("remote Core is unavailable") from exc
    if len(raw) > MAX_RESPONSE_BYTES:
        raise PublishError("remote Core response is too large")
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PublishError("remote Core returned invalid JSON") from exc
    if not isinstance(value, dict) or value.get("report_id") != envelope["report_id"]:
        raise PublishError("remote Core returned an invalid report acknowledgement")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", default="bilibili")
    parser.add_argument("--report-json", required=True, type=Path)
    parser.add_argument("--markdown", required=True, type=Path)
    parser.add_argument("--local-notice", required=True, type=Path)
    parser.add_argument("--core-url", default="https://bot.shizuki.online/meguri-core")
    parser.add_argument("--token-file", type=Path)
    parser.add_argument("--timeout-seconds", type=float, default=10.0)
    parser.add_argument("--skip-upload", action="store_true")
    args = parser.parse_args()

    try:
        report = json.loads(args.report_json.read_text(encoding="utf-8"))
        if not isinstance(report, dict):
            raise PublishError("report JSON must be an object")
        envelope = build_envelope(args.kind, report, args.markdown.read_text(encoding="utf-8"))
        write_local_notice(envelope, args.local_notice)
        if args.skip_upload:
            result = {"status": "local_only", "report_id": envelope["report_id"]}
        else:
            if args.token_file is None or not args.token_file.is_file():
                raise PublishError("Core token file is missing")
            result = upload(
                envelope,
                args.core_url,
                args.token_file.read_text(encoding="utf-8-sig"),
                args.timeout_seconds,
            )
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        return 0
    except (OSError, ValueError, json.JSONDecodeError, PublishError) as exc:
        print(
            json.dumps(
                {"status": "degraded", "error": type(exc).__name__},
                ensure_ascii=True,
                separators=(",", ":"),
            ),
            file=sys.stderr,
        )
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
