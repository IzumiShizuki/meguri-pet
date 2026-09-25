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
        entries = self._read_payload()["entries"]
        value = entries.get(self._key(session_id, turn_id))
        if not isinstance(value, dict):
            return None
        checkpoint = value.get("checkpoint")
        event_ids = value.get("seen_event_ids")
        once_event_ids = value.get("once_event_ids", [])
        if (
            not isinstance(checkpoint, int)
            or isinstance(checkpoint, bool)
            or checkpoint < 0
            or not isinstance(event_ids, list)
            or not all(isinstance(item, str) and item for item in event_ids)
            or not isinstance(once_event_ids, list)
            or not all(isinstance(item, str) and item for item in once_event_ids)
        ):
            return None
        return {
            **value,
            "seen_event_ids": set(event_ids),
            "once_event_ids": set(once_event_ids),
        }

    def save(self, session_id: str, turn_id: str, state: dict[str, Any]) -> None:
        payload = self._read_payload()
        entries = payload["entries"]
        key = self._key(session_id, turn_id)
        serializable = {
            field: sorted(value) if isinstance(value, set) else value
            for field, value in state.items()
        }
        entries.pop(key, None)
        entries[key] = serializable
        while len(entries) > self.max_entries:
            entries.pop(next(iter(entries)))
        payload["entries"] = entries
        self._write_payload(payload)

    def load_negotiation(self, client_instance_id: str) -> dict[str, Any] | None:
        value = self._read_payload()["negotiations"].get(client_instance_id)
        if not isinstance(value, dict):
            return None
        selected = value.get("selected_protocol_version")
        revision = value.get("server_capabilities_revision")
        if (
            not isinstance(selected, str)
            or not selected.startswith("1.")
            or not isinstance(revision, str)
            or not revision
        ):
            return None
        return value

    def save_negotiation(
        self, client_instance_id: str, negotiation: dict[str, Any]
    ) -> None:
        payload = self._read_payload()
        payload["negotiations"][client_instance_id] = negotiation
        self._write_payload(payload)

    def _write_payload(self, payload: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(f"{self.path.name}.tmp")
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
        temporary.replace(self.path)

    def _read_payload(self) -> dict[str, Any]:
        try:
            value = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {"version": 1, "entries": {}, "negotiations": {}}
        if not isinstance(value, dict) or value.get("version") != 1:
            return {"version": 1, "entries": {}, "negotiations": {}}
        entries = value.get("entries")
        negotiations = value.get("negotiations")
        clean_entries = {
            str(key): entry
            for key, entry in (entries.items() if isinstance(entries, dict) else [])
            if isinstance(entry, dict)
        }
        clean_negotiations = {
            str(key): entry
            for key, entry in (
                negotiations.items() if isinstance(negotiations, dict) else []
            )
            if isinstance(entry, dict)
        }
        return {
            "version": 1,
            "entries": clean_entries,
            "negotiations": clean_negotiations,
        }

    @staticmethod
    def _key(session_id: str, turn_id: str) -> str:
        material = f"{session_id}\x1f{turn_id}".encode("utf-8")
        return hashlib.sha256(material).hexdigest()
