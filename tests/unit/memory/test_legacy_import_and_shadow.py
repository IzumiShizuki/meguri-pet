from __future__ import annotations

from datetime import datetime, timezone
from uuid import uuid4

import pytest

from services.meguri_core.memory import MemoryRecord
from services.meguri_core.memory_service.enums import (
    CandidateStatus,
    MemoryType,
    SourceKind,
)
from services.meguri_core.memory_service.models import (
    MemoryCandidate,
    MemoryHit,
    MemoryScoreComponents,
    MemorySearchQuery,
)
from services.meguri_core.memory_service.providers.existing_memoryos_import import (
    MemoryOSImporter,
)
from services.meguri_core.memory_service.providers.mem0_shadow import (
    Mem0ShadowEvaluator,
    Mem0ShadowHit,
)


NOW = datetime(2026, 7, 14, tzinfo=timezone.utc)


class ReadOnlyMemoryOSSource:
    def __init__(self) -> None:
        self.calls = []

    async def list_records(self, user_id, include_deleted=False):
        self.calls.append((user_id, include_deleted))
        return [
            MemoryRecord(
                memory_id="memoryos-project-1",
                user_id=user_id,
                memory_type="ongoing_project",
                canonical_text="Meguri uses an authoritative PostgreSQL memory service",
                source_client="website",
                source_session="legacy-session",
                confidence=0.91,
                sensitivity="normal",
                importance=4,
                created_at=NOW,
                updated_at=NOW,
            ),
            MemoryRecord(
                memory_id="memoryos-transient-1",
                user_id=user_id,
                memory_type="recent_emotion",
                canonical_text="User is briefly tired",
                source_client="website",
                source_session="legacy-session",
                confidence=0.7,
                sensitivity="normal",
                importance=2,
                created_at=NOW,
                updated_at=NOW,
            ),
        ]


class CandidateDestination:
    def __init__(self) -> None:
        self.created = []

    async def create_candidate(self, candidate, *, request_id):
        result = MemoryCandidate(
            **candidate.model_dump(),
            candidate_id=uuid4(),
            status=CandidateStatus.PENDING_REVIEW,
            created_at=NOW,
            updated_at=NOW,
        )
        self.created.append((result, request_id))
        return result


@pytest.mark.asyncio
async def test_memoryos_import_is_read_only_and_creates_review_candidates() -> None:
    source = ReadOnlyMemoryOSSource()
    destination = CandidateDestination()
    importer = MemoryOSImporter(
        source,
        destination,  # type: ignore[arg-type]
        tenant_id="tenant-a",
    )

    result = await importer.import_user("user-a", batch_request_id="import-batch-1")

    assert source.calls == [("user-a", False)]
    assert result.imported == 1
    assert result.skipped == 1
    assert result.failed == 0
    candidate, request_id = destination.created[0]
    assert candidate.status is CandidateStatus.PENDING_REVIEW
    assert candidate.source_kind is SourceKind.MEMORYOS_IMPORT
    assert candidate.memory_type is MemoryType.LONG_TERM_PROJECT
    assert candidate.provenance["source_system"] == "existing_memoryos"
    assert candidate.provenance["source_record_id"] == "memoryos-project-1"
    assert request_id.startswith("import-batch-1:")


def authoritative_hit(text: str) -> MemoryHit:
    return MemoryHit(
        memory_id=uuid4(),
        version_id=uuid4(),
        memory_type=MemoryType.USER_PREFERENCE,
        content_text=text,
        score=0.9,
        score_components=MemoryScoreComponents(semantic=0.9),
        provenance={"source": "native"},
        created_at=NOW,
        updated_at=NOW,
    )


class AuthoritativeSearchStub:
    def __init__(self) -> None:
        self.search_calls = 0

    async def search(self, _query):
        self.search_calls += 1
        return [
            authoritative_hit("User prefers tea"),
            authoritative_hit("User prefers short answers"),
        ]


class Mem0SidecarStub:
    def __init__(self, *, fail=False) -> None:
        self.calls = 0
        self.fail = fail

    async def search(self, **_):
        self.calls += 1
        if self.fail:
            raise RuntimeError("sidecar credentials must not leak")
        return [
            Mem0ShadowHit(
                shadow_id="mem0-1",
                content_text="  USER PREFERS TEA  ",
                score=0.8,
            ),
            Mem0ShadowHit(
                shadow_id="mem0-2",
                content_text="User likes coffee",
                score=0.7,
            ),
        ]


def search_query() -> MemorySearchQuery:
    return MemorySearchQuery(
        tenant_id="tenant-a",
        user_id="user-a",
        query="drink and reply preferences",
        limit=5,
    )


@pytest.mark.asyncio
async def test_mem0_shadow_only_emits_aggregate_comparison() -> None:
    authority = AuthoritativeSearchStub()
    sidecar = Mem0SidecarStub()
    emitted = []
    evaluator = Mem0ShadowEvaluator(
        authority,  # type: ignore[arg-type]
        sidecar,
        enabled=True,
        sink=emitted.append,
    )

    result = await evaluator.evaluate(search_query())

    assert result.status == "ok"
    assert result.authoritative_count == 2
    assert result.shadow_count == 2
    assert result.overlap_count == 1
    assert result.overlap_at_k == 0.5
    assert len(result.query_hash) == 64
    assert "User prefers tea" not in result.model_dump_json()
    assert emitted == [result]


@pytest.mark.asyncio
async def test_disabling_or_losing_mem0_does_not_change_authoritative_results() -> None:
    authority = AuthoritativeSearchStub()
    sidecar = Mem0SidecarStub()
    disabled = Mem0ShadowEvaluator(
        authority,  # type: ignore[arg-type]
        sidecar,
        enabled=False,
    )

    disabled_result = await disabled.evaluate(search_query())
    assert disabled_result.status == "disabled"
    assert disabled_result.authoritative_count == 2
    assert sidecar.calls == 0

    failed_sidecar = Mem0SidecarStub(fail=True)
    degraded = await Mem0ShadowEvaluator(
        authority,  # type: ignore[arg-type]
        failed_sidecar,
        enabled=True,
    ).evaluate(search_query())
    assert degraded.status == "shadow_unavailable"
    assert degraded.error_code == "sidecar_failure"
    assert degraded.authoritative_count == 2
    assert "credentials" not in degraded.model_dump_json()
