from datetime import datetime, timezone
from uuid import uuid4

import pytest

from services.meguri_core.memory import (
    MemoryUpsertInput,
    SessionMessage,
    SessionSummaryInput,
)
from services.meguri_core.schemas import MemoryCandidate as RuntimeMemoryCandidate
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
    MemoryUpdate,
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
        self.summaries = []

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

    async def summarize_session(self, summary, *, request_id):
        self.summaries.append((summary, request_id))
        return summary


@pytest.mark.asyncio
async def test_legacy_auto_approval_flag_cannot_bypass_explicit_review():
    service = StubService()
    provider = NativePgvectorMemoryProvider(
        service=service,  # type: ignore[arg-type]
        tenant_id="meguri-dev",
        allow_legacy_auto_approval=True,
    )
    with pytest.raises(MemoryStateError, match="explicit approval is required"):
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
    assert service.created[0][0].tenant_id == "meguri-dev"
    assert service.created[0][0].source_kind.value == "llm_candidate"
    assert service.reviewed == []


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
    with pytest.raises(MemoryStateError, match="explicit approval is required"):
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
async def test_direct_authoritative_supersede_is_fail_closed():
    provider = NativePgvectorMemoryProvider(
        service=StubService(),  # type: ignore[arg-type]
        tenant_id="meguri-dev",
    )

    with pytest.raises(MemoryStateError, match="direct supersede is disabled"):
        await provider.supersede(
            uuid4(),
            MemoryUpdate(
                tenant_id="meguri-dev",
                user_id="user-a",
                content_text="User now prefers coffee",
                change_reason="unsafe direct update",
                base_version_id=uuid4(),
            ),
        )


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


@pytest.mark.asyncio
async def test_legacy_session_summary_persists_candidates_with_stable_idempotency():
    service = StubService()
    provider = NativePgvectorMemoryProvider(
        service=service,  # type: ignore[arg-type]
        tenant_id="meguri-dev",
    )
    summary = SessionSummaryInput(
        user_id="user-a",
        client_id="desktop_pet",
        session_id="session-a",
        messages=[
            SessionMessage(role="user", content="I am maintaining Meguri"),
            SessionMessage(role="assistant", content="Understood"),
        ],
        structured_candidates=[
            RuntimeMemoryCandidate(
                type="project",
                summary="User is maintaining Meguri",
                confidence=0.9,
                source_scope="conversation",
            )
        ],
    )

    await provider.summarize_session(summary)
    await provider.summarize_session(summary)

    persisted, first_request_id = service.summaries[0]
    assert persisted.summary_json["candidate_status"] == "audit_only"
    assert persisted.summary_json["structured_candidates"][0]["type"] == "project"
    assert first_request_id == service.summaries[1][1]
