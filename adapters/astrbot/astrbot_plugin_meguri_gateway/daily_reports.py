from __future__ import annotations

import inspect
import json
import os
import re
from collections.abc import Awaitable, Callable, Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .client import MeguriCoreClient


REPORT_ID = re.compile(r"^[a-z0-9][a-z0-9_-]{0,31}:[0-9]{4}-[0-9]{2}-[0-9]{2}$")
TARGET = re.compile(r"^[^:\s]{1,64}:(?:FriendMessage|GroupMessage|GuildMessage):[^\r\n]{1,512}$")


class DailyReportDeliveryStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.deliveries = self._load()

    def delivered(self, target: str, kind: str) -> str | None:
        value = self.deliveries.get(target, {}).get(kind)
        return value if isinstance(value, str) else None

    def mark(self, target: str, kind: str, report_id: str) -> None:
        self.deliveries.setdefault(target, {})[kind] = report_id
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(self.path.name + ".tmp")
        temporary.write_text(
            json.dumps(
                {"schema_version": 1, "deliveries": self.deliveries},
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        os.replace(temporary, self.path)

    def _load(self) -> dict[str, dict[str, str]]:
        try:
            value = json.loads(self.path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            return {}
        raw = value.get("deliveries") if isinstance(value, dict) else None
        if not isinstance(raw, dict):
            return {}
        result: dict[str, dict[str, str]] = {}
        for target, kinds in raw.items():
            if not isinstance(target, str) or not isinstance(kinds, dict):
                continue
            clean = {
                str(kind): report_id
                for kind, report_id in kinds.items()
                if isinstance(report_id, str) and REPORT_ID.fullmatch(report_id)
            }
            if clean:
                result[target] = clean
        return result


class DailyReportPoller:
    def __init__(
        self,
        core: MeguriCoreClient,
        send: Callable[[str, str], Awaitable[bool] | bool],
        *,
        targets: Sequence[str],
        kinds: Sequence[str],
        state_file: Path,
        max_age_hours: int = 48,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self.core = core
        self.send = send
        self.targets = tuple(dict.fromkeys(self._target(value) for value in targets))
        self.kinds = tuple(dict.fromkeys(self._kind(value) for value in kinds))
        self.state = DailyReportDeliveryStore(state_file)
        self.max_age_hours = max(1, min(168, int(max_age_hours)))
        self.now = now or (lambda: datetime.now(timezone.utc))

    async def poll_once(self) -> dict[str, int]:
        stats = {"reports": 0, "sent": 0, "skipped": 0}
        for kind in self.kinds:
            report = await self.core.latest_daily_report(kind)
            if report is None:
                continue
            report_id, text = self._delivery(report, kind)
            stats["reports"] += 1
            if self._is_stale(report):
                stats["skipped"] += len(self.targets)
                continue
            for target in self.targets:
                if self.state.delivered(target, kind) == report_id:
                    stats["skipped"] += 1
                    continue
                sent = self.send(target, text)
                if inspect.isawaitable(sent):
                    sent = await sent
                if sent:
                    self.state.mark(target, kind, report_id)
                    stats["sent"] += 1
        return stats

    def _delivery(self, report: dict[str, Any], kind: str) -> tuple[str, str]:
        report_id = str(report.get("report_id", ""))
        text = str(report.get("delivery_text", "")).strip()
        if not REPORT_ID.fullmatch(report_id) or not report_id.startswith(kind + ":"):
            raise ValueError("Core returned an invalid daily report ID")
        if not text or len(text) > 8000:
            raise ValueError("Core returned invalid daily report delivery text")
        return report_id, text

    def _is_stale(self, report: dict[str, Any]) -> bool:
        value = str(report.get("published_at", ""))
        try:
            published = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return True
        if published.tzinfo is None:
            return True
        age_hours = (self.now() - published.astimezone(timezone.utc)).total_seconds() / 3600
        return age_hours < -1 or age_hours > self.max_age_hours

    @staticmethod
    def _target(value: object) -> str:
        target = str(value).strip()
        if not TARGET.fullmatch(target):
            raise ValueError("daily report target must be an AstrBot unified message origin")
        return target

    @staticmethod
    def _kind(value: object) -> str:
        kind = str(value).strip()
        if not re.fullmatch(r"^[a-z0-9][a-z0-9_-]{0,31}$", kind):
            raise ValueError("daily report kind is invalid")
        return kind
