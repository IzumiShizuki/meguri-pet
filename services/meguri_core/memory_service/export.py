from __future__ import annotations

import json
from typing import Any

from .models import MemoryExport


def _line(record_type: str, payload: Any) -> str:
    if hasattr(payload, "model_dump"):
        payload = payload.model_dump(mode="json")
    return json.dumps(
        {"record_type": record_type, "data": payload},
        ensure_ascii=False,
        separators=(",", ":"),
    )


def render_memory_export_jsonl(export: MemoryExport) -> str:
    """Render a deterministic, stream-friendly export without ORM objects."""

    lines = [
        _line(
            "metadata",
            {
                "tenant_id": export.tenant_id,
                "user_id": export.user_id,
                "format": export.format,
                "generated_at": export.generated_at.isoformat(),
                "item_count": len(export.items),
                "version_count": len(export.versions),
                "audit_event_count": len(export.audit_events),
            },
        )
    ]
    lines.extend(_line("memory_item", item) for item in export.items)
    lines.extend(_line("memory_version", version) for version in export.versions)
    lines.extend(_line("audit_event", event) for event in export.audit_events)
    return "\n".join(lines) + "\n"
