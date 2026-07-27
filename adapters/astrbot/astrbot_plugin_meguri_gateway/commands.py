from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone
from typing import Literal

from pydantic import BaseModel, Field


class MeguriCommand(BaseModel):
    action: Literal[
        "status",
        "set_override",
        "clear_override",
        "chat",
        "devices",
        "run",
        "confirm",
        "task",
        "cancel",
        "help",
    ]
    override: dict = Field(default_factory=dict)
    text: str | None = None
    target_device_id: str | None = None
    draft_id: str | None = None
    confirmation_code: str | None = None
    task_id: str | None = None


_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_CONFIRMATION_CODE = re.compile(r"^[A-Za-z0-9-]{4,32}$")


def parse_command(text: str, now: datetime | None = None) -> MeguriCommand | None:
    stripped = text.strip()
    parts = stripped.split()
    if not parts or parts[0].lower() != "/meguri":
        return None
    if len(parts) == 1 or parts[1].lower() == "help":
        return MeguriCommand(action="help")
    command = parts[1].lower()
    if command == "chat":
        content = stripped.split(maxsplit=2)[2].strip() if len(parts) >= 3 else ""
        return (
            MeguriCommand(action="chat", text=content)
            if content
            else MeguriCommand(action="help")
        )
    if command == "devices" and len(parts) == 2:
        return MeguriCommand(action="devices")
    if command == "run":
        return _parse_remote_run(stripped)
    if (
        command == "confirm"
        and len(parts) == 4
        and _IDENTIFIER.fullmatch(parts[2])
        and _CONFIRMATION_CODE.fullmatch(parts[3])
    ):
        return MeguriCommand(
            action="confirm",
            draft_id=parts[2],
            confirmation_code=parts[3],
        )
    if command == "task" and len(parts) == 3 and _IDENTIFIER.fullmatch(parts[2]):
        return MeguriCommand(action="task", task_id=parts[2])
    if command == "cancel" and len(parts) == 3 and _IDENTIFIER.fullmatch(parts[2]):
        return MeguriCommand(action="cancel", task_id=parts[2])
    if command == "status":
        return MeguriCommand(action="status")
    if command == "reset" or (
        command in {"auto", "outfit"}
        and len(parts) >= 3
        and parts[2].lower() == "auto"
    ):
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


def _parse_remote_run(text: str) -> MeguriCommand:
    remainder = text.split(maxsplit=2)[2].strip() if len(text.split(maxsplit=2)) == 3 else ""
    if not remainder:
        return MeguriCommand(action="help")
    target_device_id: str | None = None
    if remainder.startswith("--device "):
        device_parts = remainder.split(maxsplit=2)
        if len(device_parts) != 3:
            return MeguriCommand(action="help")
        target_device_id = device_parts[1].strip()
        if not _IDENTIFIER.fullmatch(target_device_id):
            return MeguriCommand(action="help")
        remainder = device_parts[2].strip()
    if not remainder or len(remainder) > 4000:
        return MeguriCommand(action="help")
    return MeguriCommand(
        action="run",
        text=remainder,
        target_device_id=target_device_id,
    )


def _parse_duration(value: str) -> timedelta | None:
    if len(value) < 2 or not value[:-1].isdigit():
        return None
    digits, unit = value[:-1], value[-1].lower()
    limits = {"m": 7 * 24 * 60, "h": 7 * 24, "d": 7}
    limit = limits.get(unit)
    if limit is None or len(digits) > len(str(limit)):
        return None
    amount = int(digits)
    if amount <= 0 or amount > limit:
        return None
    if unit == "m":
        return timedelta(minutes=amount)
    if unit == "h":
        return timedelta(hours=amount)
    return timedelta(days=amount)


HELP_TEXT = """Meguri commands:
/meguri chat <message>
/meguri status
/meguri mode work|private|sleep|event [2h]
/meguri outfit auto|01..06 [2h]
/meguri relation sibling|pursuit|lover [2h]
/meguri reset
/meguri devices
/meguri run [--device <device_id>] <task>
/meguri confirm <draft_id> <code>
/meguri task <task_id>
/meguri cancel <task_id>"""
