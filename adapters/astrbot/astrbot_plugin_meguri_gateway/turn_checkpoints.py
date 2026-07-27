from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any


class TurnCheckpointStore:
    """Small atomic store for resumable AstrBot Turn consumers."""

    def __init__(self, path: str | Path, *, max_entries: int = 256) -> None:
        self.path = Path(path)
        if max_entries < 1:
            raise ValueError("turn checkpoint max_entries must be positive")
        self.max_entries = max_entries

    def load(self, session_id: str, turn_id: str) -> dict[str, Any] | None:
        entries = self._read_entries()
        value = entries.get(self._key(session_id, turn_id))
        if not isinstance(value, dict):
            return None
        checkpoint = value.get("checkpoint")
        event_ids = value.get("seen_event_ids")
        if (
            not isinstance(checkpoint, int)
            or isinstance(checkpoint, bool)
            or checkpoint < 0
            or not isinstance(event_ids, list)
            or not all(isinstance(item, str) and item for item in event_ids)
        ):
            return None
        return {**value, "seen_event_ids": set(event_ids)}

    def save(self, session_id: str, turn_id: str, state: dict[str, Any]) -> None:
        entries = self._read_entries()
        key = self._key(session_id, turn_id)
        serializable = {
            field: sorted(value) if isinstance(value, set) else value
            for field, value in state.items()
        }
        entries.pop(key, None)
        entries[key] = serializable
        while len(entries) > self.max_entries:
            entries.pop(next(iter(entries)))
        payload = {"version": 1, "entries": entries}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(f"{self.path.name}.tmp")
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
        temporary.replace(self.path)

    def _read_entries(self) -> dict[str, dict[str, Any]]:
        try:
            value = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}
        if not isinstance(value, dict) or value.get("version") != 1:
            return {}
        entries = value.get("entries")
        if not isinstance(entries, dict):
            return {}
        return {
            str(key): entry
            for key, entry in entries.items()
            if isinstance(entry, dict)
        }

    @staticmethod
    def _key(session_id: str, turn_id: str) -> str:
        material = f"{session_id}\x1f{turn_id}".encode("utf-8")
        return hashlib.sha256(material).hexdigest()
