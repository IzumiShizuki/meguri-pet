from datetime import datetime, timezone
from uuid import uuid4

import pytest

from services.meguri_core.memory import MemoryUpsertInput
from services.meguri_core.memory_service.enums import (
    ActorType,
    CandidateStatus,
    MemoryScope,
    MemoryStatus,
    MemoryType,
    SearchMode,
)
from services.meguri_core.memory_service.embedding import BgeM3EmbeddingProvider
from services.meguri_core.memory_service.contracts import MemoryStateError
from services.meguri_core.memory_service.models import (
    MemoryCandidate,
    MemoryItem,
    MemorySearchQuery,
    MemoryVersion,
)
from services.meguri_core.memory_service.native_pgvector import (
    NativePgvectorMemoryProvider,
)


NOW = datetime(2026, 7, 14, tzinfo=timezone.utc)


class StubService:
    def __init__(self):
        self.uow_factory = lambda: None
        self.created = []
        self.reviewed = []
        self.searches = []

    async def search(self, query):
        self.searches.append(query)
        return []

    async def create_candidate(self, candidate, *, request_id):
        self.created.append((candidate, request_id))
        return MemoryCandidate(
            **candidate.model_dump(),
            candidate_id=uuid4(),
            status=CandidateStatus.PENDING_REVIEW,
            created_at=NOW,
            updated_at=NOW,
        )

    async def review_candidate(self, candidate_id, decision, *, actor, request_id):
        self.reviewed.append((candidate_id, decision, actor, request_id))
        memory_id = uuid4()
        version = MemoryVersion(
            version_id=uuid4(),
            memory_id=memory_id,
            version_no=1,
            content_text=self.created[-1][0].content_text,
            content_json={},
            change_reason="approved",
            provenance={"source_client_id": "website", "source_session_id": "session-web"},
            created_by_type=ActorType.POLICY,
            created_at=NOW,
        )
        return MemoryItem(
            memory_id=memory_id,
            tenant_id="meguri-dev",
            user_id="user-a",
            memory_type=MemoryType.USER_PREFERENCE,
            scope=MemoryScope.GLOBAL_USER,
            status=MemoryStatus.ACTIVE,
            current_version_id=version.version_id,
            importance=0.6,
            confidence=0.9,
            created_at=NOW,
            updated_at=NOW,
            current_version=version,
        )


@pytest.mark.asyncio
async def test_legacy_upsert_runs_candidate_and_review_flow():
    service = StubService()
    provider = NativePgvectorMemoryProvider(
        service=service,  # type: ignore[arg-type]
        tenant_id="meguri-dev",
        allow_legacy_auto_approval=True,
    )
    record = await provider.upsert(
        MemoryUpsertInput(
            user_id="user-a",
            memory_type="preference",
            canonical_text="User prefers tea",
            source_client="website",
            source_session="session-web",
            confidence=0.9,
        )
    )
    assert record.canonical_text == "User prefers tea"
    assert record.status == "active"
    assert service.created[0][0].tenant_id == "meguri-dev"
    assert service.created[0][0].source_kind.value == "llm_candidate"
    assert service.reviewed[0][1].decision.value == "approve"


@pytest.mark.asyncio
async def test_legacy_upsert_rejects_short_term_state():
    provider = NativePgvectorMemoryProvider(
        service=StubService(),  # type: ignore[arg-type]
        tenant_id="meguri-dev",
    )
    with pytest.raises(ValueError):
        await provider.upsert(
            MemoryUpsertInput(
                user_id="user-a",
                memory_type="recent_emotion",
                canonical_text="User is briefly upset",
                source_client="website",
                source_session="session-web",
                confidence=0.9,
            )
        )


@pytest.mark.asyncio
async def test_legacy_upsert_queues_without_explicit_compatibility_flag():
    service = StubService()
    provider = NativePgvectorMemoryProvider(
        service=service,  # type: ignore[arg-type]
        tenant_id="meguri-dev",
        allow_legacy_auto_approval=False,
    )
    with pytest.raises(MemoryStateError, match="automatic approval is disabled"):
        await provider.upsert(
            MemoryUpsertInput(
                user_id="user-a",
                memory_type="preference",
                canonical_text="User prefers tea",
                source_client="website",
                source_session="session-web",
                confidence=0.9,
            )
        )
    assert len(service.created) == 1
    assert service.reviewed == []


@pytest.mark.asyncio
async def test_authoritative_search_generates_pinned_query_embedding():
    service = StubService()
    embedding = BgeM3EmbeddingProvider(
        revision="0123456789abcdef",
        embed_callable=lambda texts: [[0.25] * 1024 for _ in texts],
    )
    provider = NativePgvectorMemoryProvider(
        service=service,  # type: ignore[arg-type]
        tenant_id="meguri-dev",
        query_embedding_provider=embedding,
    )

    await provider.search(
        MemorySearchQuery(
            tenant_id="meguri-dev",
            user_id="user-a",
            query="tea preference",
            modes=[SearchMode.HYBRID],
        )
    )

    query = service.searches[0]
    assert query.query_embedding == [0.25] * 1024
    assert query.embedding_model == "BAAI/bge-m3"
    assert query.embedding_revision == "0123456789abcdef"
