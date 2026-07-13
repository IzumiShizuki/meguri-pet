from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Literal

from pydantic import BaseModel, Field


class MeguriCommand(BaseModel):
    action: Literal["status", "set_override", "clear_override", "help"]
    override: dict = Field(default_factory=dict)


def parse_command(text: str, now: datetime | None = None) -> MeguriCommand | None:
    stripped = text.strip()
    if not stripped.lower().startswith("/meguri"):
        return None
    parts = stripped.split()
    if len(parts) == 1 or parts[1].lower() == "help":
        return MeguriCommand(action="help")
    command = parts[1].lower()
    if command == "status":
        return MeguriCommand(action="status")
    if command == "reset" or (command in {"auto", "outfit"} and len(parts) >= 3 and parts[2].lower() == "auto"):
        return MeguriCommand(action="clear_override")
    if command not in {"mode", "outfit", "relation"} or len(parts) < 3:
        return MeguriCommand(action="help")
    value = parts[2].lower()
    field = {
        "mode": "mode",
        "outfit": "outfit_code",
        "relation": "relationship_profile",
    }[command]
    allowed = {
        "mode": {"work", "private", "sleep", "event"},
        "outfit_code": {"01", "02", "03", "04", "05", "06"},
        "relationship_profile": {"sibling", "pursuit", "lover"},
    }[field]
    if value not in allowed:
        return MeguriCommand(action="help")
    override: dict = {field: value}
    if len(parts) >= 4:
        duration = _parse_duration(parts[3])
        if duration is None:
            return MeguriCommand(action="help")
        current = now or datetime.now(timezone.utc)
        override["expires_at"] = (current + duration).isoformat()
    return MeguriCommand(action="set_override", override=override)


def _parse_duration(value: str) -> timedelta | None:
    if len(value) < 2 or not value[:-1].isdigit():
        return None
    amount, unit = int(value[:-1]), value[-1].lower()
    if amount <= 0:
        return None
    if unit == "m":
        return timedelta(minutes=amount)
    if unit == "h":
        return timedelta(hours=amount)
    if unit == "d":
        return timedelta(days=amount)
    return None


HELP_TEXT = (
    "/meguri status | mode work|private|sleep [2h] | outfit auto|01..06 [2h] | "
    "relation sibling|pursuit|lover [2h] | reset"
)
