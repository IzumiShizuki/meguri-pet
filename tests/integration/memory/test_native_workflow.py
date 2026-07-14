from __future__ import annotations

import asyncio
import os
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select

from services.meguri_core.memory_service.database import MemoryDatabaseSettings
from services.meguri_core.memory_service.embedding import (
    BgeM3EmbeddingProvider,
    EmbeddingWorker,
)
from services.meguri_core.memory_service.enums import (
    ActorType,
    CandidateStatus,
    SearchMode,
)
from services.meguri_core.memory_service.models import (
    CandidateReview,
    IdentityBindingCreate,
    MemoryActor,
    MemoryCandidateCreate,
    MemoryFeedbackCreate,
    MemorySearchQuery,
    MemoryUpdate,
    SessionSummaryUpsert,
)
from services.meguri_core.memory_service.native_pgvector import (
    NativePgvectorMemoryProvider,
)
from services.meguri_core.memory_service.orm import SessionSummaryRow
from services.meguri_core.memory_service.release import EMBEDDING_MODEL_REVISION


@pytest_asyncio.fixture
async def native_provider():
    database_url = os.getenv("MEGURI_TEST_DATABASE_URL")
    if not database_url:
        pytest.skip("MEGURI_TEST_DATABASE_URL is required for native workflow tests")
    provider = NativePgvectorMemoryProvider(
        settings=MemoryDatabaseSettings(
            environment="dev",
            tenant_id="unused-default",
            database_url=database_url,
            mutation_allowed=True,
        )
    )
    try:
        yield provider
    finally:
        await provider.close()


def candidate(tenant_id: str, user_id: str, text: str, *, client="website"):
    token = uuid4().hex
    return MemoryCandidateCreate(
        tenant_id=tenant_id,
        user_id=user_id,
        memory_type="user_preference",
        content_text=text,
        confidence=0.95,
        source_client_id=client,
        source_session_id=f"session-{token}",
        source_turn_id=f"turn-{token}",
        source_kind="direct_user",
    )


@pytest.mark.asyncio
async def test_candidate_version_delete_restore_and_environment_isolation(
    native_provider,
) -> None:
    tenant_id = f"tenant-{uuid4().hex}"
    other_tenant = f"tenant-{uuid4().hex}"
    user_id = f"user-{uuid4().hex}"
    admin = MemoryActor(actor_type=ActorType.ADMIN, actor_id="integration-admin")
    created = await native_provider.create_candidate(
        candidate(tenant_id, user_id, "User prefers jasmine tea"),
        request_id=f"create-{uuid4()}",
    )
    item = await native_provider.review_candidate(
        created.candidate_id,
        CandidateReview(decision="approve", reason="integration approval"),
        actor=admin,
        request_id=f"approve-{uuid4()}",
    )
    assert item is not None

    initial_hits = await native_provider.search(
        MemorySearchQuery(
            tenant_id=tenant_id,
            user_id=user_id,
            query="jasmine tea",
            modes=[SearchMode.KEYWORD],
        )
    )
    assert initial_hits and initial_hits[0].version_id == item.current_version_id
    assert await native_provider.search(
        MemorySearchQuery(
            tenant_id=other_tenant,
            user_id=user_id,
            query="jasmine tea",
            modes=[SearchMode.KEYWORD],
        )
    ) == []

    updated = await native_provider.supersede(
        item.memory_id,
        MemoryUpdate(
            tenant_id=tenant_id,
            user_id=user_id,
            content_text="User now prefers black coffee",
            change_reason="explicit correction",
        ),
        actor=MemoryActor(actor_type=ActorType.USER, actor_id=user_id),
        request_id=f"supersede-{uuid4()}",
    )

    rejected = await native_provider.create_candidate(
        candidate(tenant_id, user_id, "User prefers mint tea"),
        request_id=f"create-rejected-{uuid4()}",
    )
    assert await native_provider.review_candidate(
        rejected.candidate_id,
        CandidateReview(decision="reject", reason="not a durable preference"),
        actor=admin,
        request_id=f"reject-{uuid4()}",
    ) is None
    assert updated.current_version is not None
    assert updated.current_version.version_no == 2
    current_hits = await native_provider.search(
        MemorySearchQuery(
            tenant_id=tenant_id,
            user_id=user_id,
            query="black coffee",
            modes=[SearchMode.KEYWORD],
        )
    )
    assert current_hits and current_hits[0].version_id == updated.current_version_id
    recorded_feedback = await native_provider.record_feedback(
        MemoryFeedbackCreate(
            tenant_id=tenant_id,
            user_id=user_id,
            memory_id=item.memory_id,
            version_id=updated.current_version_id,
            feedback_kind="false_recall",
            query_text="black coffee",
            hit_rank=1,
        ),
        request_id=f"feedback-{uuid4()}",
    )
    assert recorded_feedback.version_id == updated.current_version_id

    await native_provider.delete(
        item.memory_id,
        tenant_id=tenant_id,
        user_id=user_id,
        reason="integration delete",
        actor=MemoryActor(actor_type=ActorType.USER, actor_id=user_id),
        request_id=f"delete-{uuid4()}",
    )
    assert await native_provider.search(
        MemorySearchQuery(
            tenant_id=tenant_id,
            user_id=user_id,
            query="black coffee",
            modes=[SearchMode.KEYWORD],
        )
    ) == []
    await native_provider.restore(
        item.memory_id,
        tenant_id=tenant_id,
        user_id=user_id,
        actor=MemoryActor(actor_type=ActorType.USER, actor_id=user_id),
        request_id=f"restore-{uuid4()}",
    )
    assert await native_provider.search(
        MemorySearchQuery(
            tenant_id=tenant_id,
            user_id=user_id,
            query="black coffee",
            modes=[SearchMode.KEYWORD],
        )
    )


@pytest.mark.asyncio
async def test_repository_transaction_rolls_back_candidate(native_provider) -> None:
    tenant_id = f"tenant-{uuid4().hex}"
    user_id = f"user-{uuid4().hex}"
    with pytest.raises(RuntimeError, match="injected rollback"):
        async with native_provider.uow_factory() as uow:
            assert uow.repository is not None
            await uow.repository.create_candidate(
                candidate(tenant_id, user_id, "User prefers rollback safety"),
                status=CandidateStatus.PENDING_REVIEW,
            )
            raise RuntimeError("injected rollback")
    assert await native_provider.list_candidates(
        tenant_id=tenant_id,
        user_id=user_id,
    ) == []


@pytest.mark.asyncio
async def test_concurrent_request_id_creates_one_candidate(native_provider) -> None:
    tenant_id = f"tenant-{uuid4().hex}"
    user_id = f"user-{uuid4().hex}"
    request_id = f"same-request-{uuid4()}"
    proposed = candidate(
        tenant_id,
        user_id,
        "User prefers idempotent memory writes",
    )

    first, second = await asyncio.gather(
        native_provider.create_candidate(proposed, request_id=request_id),
        native_provider.create_candidate(proposed, request_id=request_id),
    )

    assert first.candidate_id == second.candidate_id
    candidates = await native_provider.list_candidates(
        tenant_id=tenant_id,
        user_id=user_id,
    )
    assert [entry.candidate_id for entry in candidates].count(first.candidate_id) == 1


@pytest.mark.asyncio
async def test_identity_cross_client_and_session_summary_isolation(native_provider) -> None:
    tenant_id = f"tenant-{uuid4().hex}"
    user_id = f"user-{uuid4().hex}"
    admin = MemoryActor(actor_type=ActorType.ADMIN, actor_id="integration-admin")
    for platform, platform_user_id in (
        ("website", f"web-{uuid4().hex}"),
        ("astrbot", f"qq-{uuid4().hex}"),
        ("airi", f"airi-{uuid4().hex}"),
    ):
        await native_provider.bind_identity(
            IdentityBindingCreate(
                tenant_id=tenant_id,
                user_id=user_id,
                platform=platform,
                platform_user_id=platform_user_id,
                verification_method="integration_signed_challenge",
            ),
            actor=admin,
            request_id=f"bind-{uuid4()}",
        )
        assert await native_provider.resolve_identity(
            tenant_id=tenant_id,
            platform=platform,
            platform_user_id=platform_user_id,
        ) == user_id

    for client_id, session_id in (
        ("website", "web-session"),
        ("astrbot", "qq-session"),
        ("airi", "airi-session"),
    ):
        await native_provider.summarize_session(
            SessionSummaryUpsert(
                tenant_id=tenant_id,
                user_id=user_id,
                client_id=client_id,
                session_id=session_id,
                summary_text=f"summary for {client_id}",
                source_range={"start": 1, "end": 2},
            ),
            request_id=f"summary-{uuid4()}",
        )
    async with native_provider.uow_factory() as uow:
        rows = list(
            (
                await uow.session.scalars(
                    select(SessionSummaryRow).where(
                        SessionSummaryRow.tenant_id == tenant_id,
                        SessionSummaryRow.user_id == user_id,
                    )
                )
            ).all()
        )
    assert {(row.client_id, row.session_id) for row in rows} == {
        ("website", "web-session"),
        ("astrbot", "qq-session"),
        ("airi", "airi-session"),
    }


@pytest.mark.asyncio
async def test_outbox_workers_claim_once_and_write_pinned_embedding(native_provider) -> None:
    tenant_id = f"tenant-{uuid4().hex}"
    user_id = f"user-{uuid4().hex}"
    created = await native_provider.create_candidate(
        candidate(tenant_id, user_id, "User prefers calm background music"),
        request_id=f"create-{uuid4()}",
    )
    item = await native_provider.review_candidate(
        created.candidate_id,
        CandidateReview(decision="approve", reason="integration approval"),
        actor=MemoryActor(actor_type=ActorType.ADMIN, actor_id="integration-admin"),
        request_id=f"approve-{uuid4()}",
    )
    assert item is not None
    vector = [1.0, *([0.0] * 1023)]
    embedding = BgeM3EmbeddingProvider(
        revision=EMBEDDING_MODEL_REVISION,
        embed_callable=lambda texts: [vector for _ in texts],
    )
    workers = [
        EmbeddingWorker(
            native_provider.uow_factory,
            embedding,
            worker_id=f"worker-{index}",
        )
        for index in range(2)
    ]
    results = await asyncio.gather(*(worker.run_once() for worker in workers))
    assert sum(result["completed"] for result in results) >= 1
    assert sum(result["failed"] for result in results) == 0
    hits = await native_provider.search(
        MemorySearchQuery(
            tenant_id=tenant_id,
            user_id=user_id,
            query="calm background music",
            modes=[SearchMode.EXACT_VECTOR],
            query_embedding=vector,
            embedding_model=embedding.model,
            embedding_revision=embedding.revision,
        )
    )
    assert hits and hits[0].memory_id == item.memory_id
