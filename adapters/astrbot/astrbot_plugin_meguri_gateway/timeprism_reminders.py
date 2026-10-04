"""Owner-only proactive QQ delivery for TimePrism reminder occurrences."""

from __future__ import annotations

import inspect
import json
import os
import re
import time
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

from .client import MeguriCoreClient


EVENT_ID = re.compile(r"^[0-9a-f]{64}$")
FRIEND_TARGET = re.compile(r"^[^:\s]{1,64}:FriendMessage:[^\r\n]{1,512}$")
DUE_AT = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$")


class TimePrismReminderPoller:
    def __init__(
        self,
        core: MeguriCoreClient,
        send: Callable[[str, str], Awaitable[bool] | bool],
        *,
        owner_user_id: int,
        target: str,
        state_file: Path,
        max_age_minutes: int = 30,
    ) -> None:
        if not isinstance(owner_user_id, int) or owner_user_id <= 0:
            raise ValueError("owner Shizuki user ID is required")
        if not FRIEND_TARGET.fullmatch(target):
            raise ValueError("reminder target must be an exact private FriendMessage origin")
        self.core = core
        self.send = send
        self.owner_user_id = owner_user_id
        self.target = target
        self.state_file = state_file
        self.max_age_ms = max(1, min(1440, int(max_age_minutes))) * 60_000
        self.cursor = self._load_cursor()

    def _load_cursor(self) -> int:
        try:
            saved = json.loads(self.state_file.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            return 0
        if not isinstance(saved, dict) or saved.get("owner_user_id") != self.owner_user_id or saved.get("target") != self.target:
            return 0
        cursor = saved.get("cursor")
        return cursor if isinstance(cursor, int) and cursor >= 0 else 0

    def _save_cursor(self) -> None:
        self.state_file.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.state_file.with_name(self.state_file.name + ".tmp")
        temporary.write_text(json.dumps({
            "version": 1,
            "owner_user_id": self.owner_user_id,
            "target": self.target,
            "cursor": self.cursor,
        }), encoding="utf-8")
        os.replace(temporary, self.state_file)

    async def poll_once(self) -> int:
        feed = await self.core.timeprism_reminders(self.cursor)
        events = feed.get("events")
        if not isinstance(events, list) or len(events) > 100:
            raise ValueError("Core returned an invalid reminder feed")
        sent = 0
        for event in events:
            sequence, text, trigger_at = self._event(event)
            if sequence <= self.cursor:
                continue
            if sequence != self.cursor + 1:
                # Core retains only a bounded window. Resume at the first item still present.
                self.cursor = sequence - 1
                self._save_cursor()
            age_ms = int(time.time() * 1000) - trigger_at
            if age_ms < -60_000 or age_ms > self.max_age_ms:
                self.cursor = sequence
                self._save_cursor()
                continue
            delivered = self.send(self.target, text)
            if inspect.isawaitable(delivered):
                delivered = await delivered
            if not delivered:
                break
            self.cursor = sequence
            self._save_cursor()
            sent += 1
        return sent

    def _event(self, event: Any) -> tuple[int, str, int]:
        if not isinstance(event, dict):
            raise ValueError("Core returned a non-object reminder")
        sequence = event.get("sequence")
        event_id = event.get("eventId")
        owner = event.get("shizukiUserId")
        title = event.get("title")
        due_at = event.get("dueAt")
        trigger_at = event.get("triggerAt")
        if (not isinstance(sequence, int) or sequence <= 0
                or not isinstance(event_id, str) or not EVENT_ID.fullmatch(event_id)
                or owner != self.owner_user_id
                or not isinstance(title, str) or not title.strip() or len(title) > 200
                or any(ord(char) < 32 for char in title)
                or not isinstance(due_at, str) or not DUE_AT.fullmatch(due_at)
                or not isinstance(trigger_at, int) or trigger_at <= 0):
            raise ValueError("Core returned an invalid owner reminder")
        return sequence, f"⏰ Meguri 待办提醒\n{title}\n时间：{due_at.replace('T', ' ')}", trigger_at
