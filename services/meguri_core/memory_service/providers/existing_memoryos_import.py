from __future__ import annotations

import hashlib
from typing import Protocol
from uuid import UUID

from pydantic import Field

from services.meguri_core.memory import MemoryRecord

from ..contracts import AuthoritativeMemoryProvider
from ..enums import MemoryType, Sensitivity, SourceKind
from ..models import MemoryCandidateCreate, StrictModel


class MemoryOSReadSource(Protocol):
    async def list_records(
        self, user_id: str, include_deleted: bool = False
    ) -> list[MemoryRecord]: ...


LEGACY_IMPORT_TYPES: dict[str, MemoryType] = {
    "core_profile": MemoryType.USER_PROFILE,
    "preference": MemoryType.USER_PREFERENCE,
    "relationship": MemoryType.RELATIONSHIP_FACT,
    "shared_experience": MemoryType.RELATIONSHIP_FACT,
    "promise": MemoryType.COMMITMENT,
    "important_person": MemoryType.IMPORTANT_PERSON,
    "ongoing_project": MemoryType.LONG_TERM_PROJECT,
    "episodic": MemoryType.CORRECTED_FACT,
}


class MemoryOSImportResult(StrictModel):
    imported: int = Field(ge=0)
    skipped: int = Field(ge=0)
    failed: int = Field(ge=0)
    candidate_ids: list[UUID] = Field(default_factory=list)


class MemoryOSImporter:
    """Read historical MemoryOS journals and create reviewable candidates only."""

    def __init__(
        self,
        source: MemoryOSReadSource,
        destination: AuthoritativeMemoryProvider,
        *,
        tenant_id: str,
    ) -> None:
        self.source = source
        self.destination = destination
        self.tenant_id = tenant_id

    async def import_user(
        self,
        user_id: str,
        *,
        batch_request_id: str,
    ) -> MemoryOSImportResult:
        records = await self.source.list_records(user_id, include_deleted=False)
        candidate_ids: list[UUID] = []
        skipped = failed = 0
        for record in records:
            mapped_type = LEGACY_IMPORT_TYPES.get(record.memory_type)
            if mapped_type is None or record.status != "active":
                skipped += 1
                continue
            request_suffix = hashlib.sha256(
                record.memory_id.encode("utf-8")
            ).hexdigest()[:24]
            try:
                candidate = await self.destination.create_candidate(
                    MemoryCandidateCreate(
                        tenant_id=self.tenant_id,
                        user_id=user_id,
                        memory_type=mapped_type,
                        content_text=record.canonical_text,
                        content_json={
                            "legacy_memory_type": record.memory_type,
                            "legacy_importance": record.importance,
                        },
                        confidence=record.confidence,
                        sensitivity=Sensitivity(record.sensitivity),
                        source_client_id=record.source_client or "memoryos",
                        source_session_id=record.source_session or "memoryos-import",
                        source_turn_id=record.memory_id,
                        source_message_ids=[],
                        source_kind=SourceKind.MEMORYOS_IMPORT,
                        provenance={
                            "source_system": "existing_memoryos",
                            "source_record_id": record.memory_id,
                            "source_created_at": record.created_at.isoformat(),
                            "source_version": record.version,
                        },
                    ),
                    request_id=f"{batch_request_id}:{request_suffix}"[:200],
                )
            except Exception:
                failed += 1
                continue
            candidate_ids.append(candidate.candidate_id)
        return MemoryOSImportResult(
            imported=len(candidate_ids),
            skipped=skipped,
            failed=failed,
            candidate_ids=candidate_ids,
        )
