from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import and_, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from .enums import (
    ActorType,
    AuditAction,
    CandidateStatus,
    EmbeddingStatus,
    IdentityBindingStatus,
    MemoryScope,
    MemoryStatus,
    OutboxStatus,
)
from .models import (
    IdentityBinding,
    IdentityBindingCreate,
    MemoryActor,
    MemoryCandidate,
    MemoryCandidateCreate,
    MemoryItem,
    MemorySearchQuery,
    MemoryUpdate,
    MemoryVersion,
    SessionSummaryUpsert,
)
from .orm import (
    IdentityBindingRow,
    MemoryAuditLogRow,
    MemoryCandidateRow,
    MemoryEmbeddingRow,
    MemoryIdempotencyRow,
    MemoryItemRow,
    MemoryOutboxRow,
    MemoryVersionRow,
    SessionSummaryRow,
)


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


@dataclass(frozen=True)
class RetrievedItem:
    item: MemoryItem
    semantic: float = 0.0
    keyword: float = 0.0


def candidate_model(row: MemoryCandidateRow) -> MemoryCandidate:
    return MemoryCandidate(
        candidate_id=row.candidate_id,
        tenant_id=row.tenant_id,
        user_id=row.user_id,
        memory_type=row.memory_type,
        content_text=row.content_text,
        content_json=row.content_json,
        confidence=row.confidence,
        sensitivity=row.sensitivity,
        source_client_id=row.source_client_id,
        source_session_id=row.source_session_id,
        source_turn_id=row.source_turn_id,
        source_message_ids=row.source_message_ids,
        source_kind=row.source_kind,
        extraction_model=row.extraction_model,
        extraction_prompt_hash=row.extraction_prompt_hash,
        provenance=row.provenance,
        status=row.status,
        review_reason=row.review_reason,
        reviewed_by=row.reviewed_by,
        reviewed_at=row.reviewed_at,
        accepted_memory_id=row.accepted_memory_id,
        created_at=row.created_at,
        updated_at=row.updated_at,
    )


def version_model(row: MemoryVersionRow) -> MemoryVersion:
    return MemoryVersion(
        version_id=row.version_id,
        memory_id=row.memory_id,
        version_no=row.version_no,
        content_text=row.content_text,
        content_json=row.content_json,
        language=row.language,
        relationship_stage=row.relationship_stage,
        supersedes_version_id=row.supersedes_version_id,
        change_reason=row.change_reason,
        provenance=row.provenance,
        created_by_type=row.created_by_type,
        created_by_id=row.created_by_id,
        created_at=row.created_at,
    )


def item_model(row: MemoryItemRow, version: MemoryVersionRow) -> MemoryItem:
    return MemoryItem(
        memory_id=row.memory_id,
        tenant_id=row.tenant_id,
        user_id=row.user_id,
        memory_type=row.memory_type,
        scope=row.scope,
        status=row.status,
        canonical_key=row.canonical_key,
        current_version_id=version.version_id,
        importance=row.importance,
        confidence=row.confidence,
        effective_at=row.effective_at,
        expires_at=row.expires_at,
        created_at=row.created_at,
        updated_at=row.updated_at,
        deleted_at=row.deleted_at,
        current_version=version_model(version),
    )


def binding_model(row: IdentityBindingRow) -> IdentityBinding:
    return IdentityBinding(
        binding_id=row.binding_id,
        tenant_id=row.tenant_id,
        user_id=row.user_id,
        platform=row.platform,
        platform_user_id=row.platform_user_id,
        verification_method=row.verification_method or "unknown",
        status=row.status,
        verified_at=row.verified_at,
        created_at=row.created_at,
        updated_at=row.updated_at,
    )


class SqlAlchemyMemoryRepository:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def get_idempotent(
        self, tenant_id: str, operation: str, request_id: str
    ) -> dict[str, Any] | None:
        result = await self.session.scalar(
            select(MemoryIdempotencyRow).where(
                MemoryIdempotencyRow.tenant_id == tenant_id,
                MemoryIdempotencyRow.operation == operation,
                MemoryIdempotencyRow.request_id == request_id,
            )
        )
        return dict(result.response_json) if result else None

    async def put_idempotent(
        self,
        tenant_id: str,
        operation: str,
        request_id: str,
        response_json: dict[str, Any],
    ) -> None:
        self.session.add(
            MemoryIdempotencyRow(
                idempotency_id=uuid4(),
                tenant_id=tenant_id,
                operation=operation,
                request_id=request_id,
                response_json=response_json,
            )
        )
        await self.session.flush()

    async def create_candidate(
        self,
        candidate: MemoryCandidateCreate,
        *,
        status: CandidateStatus,
        review_reason: str | None = None,
    ) -> MemoryCandidate:
        row = MemoryCandidateRow(
            candidate_id=uuid4(),
            **candidate.model_dump(mode="json"),
            status=status.value,
            review_reason=review_reason,
        )
        self.session.add(row)
        await self.session.flush()
        return candidate_model(row)

    async def get_candidate_for_update(self, candidate_id: UUID) -> MemoryCandidateRow | None:
        return await self.session.scalar(
            select(MemoryCandidateRow)
            .where(MemoryCandidateRow.candidate_id == candidate_id)
            .with_for_update()
        )

    async def finish_candidate(
        self,
        row: MemoryCandidateRow,
        *,
        status: CandidateStatus,
        actor: MemoryActor,
        reason: str,
        accepted_memory_id: UUID | None = None,
    ) -> MemoryCandidate:
        row.status = status.value
        row.reviewed_by = actor.actor_id
        row.reviewed_at = utc_now()
        row.updated_at = row.reviewed_at
        row.review_reason = reason
        row.accepted_memory_id = accepted_memory_id
        await self.session.flush()
        return candidate_model(row)

    async def append_audit(
        self,
        *,
        tenant_id: str,
        request_id: str,
        action: AuditAction,
        aggregate_type: str,
        aggregate_id: str,
        actor: MemoryActor,
        details: dict[str, Any] | None = None,
    ) -> None:
        self.session.add(
            MemoryAuditLogRow(
                tenant_id=tenant_id,
                request_id=request_id,
                action=action.value,
                aggregate_type=aggregate_type,
                aggregate_id=aggregate_id,
                actor_type=actor.actor_type.value,
                actor_id=actor.actor_id,
                details=details or {},
            )
        )

    async def get_item(
        self,
        memory_id: UUID,
        *,
        tenant_id: str,
        user_id: str,
        for_update: bool = False,
    ) -> MemoryItem | None:
        statement = (
            select(MemoryItemRow, MemoryVersionRow)
            .join(
                MemoryVersionRow,
                and_(
                    MemoryVersionRow.memory_id == MemoryItemRow.memory_id,
                    MemoryVersionRow.version_id == MemoryItemRow.current_version_id,
                ),
            )
            .where(
                MemoryItemRow.memory_id == memory_id,
                MemoryItemRow.tenant_id == tenant_id,
                MemoryItemRow.user_id == user_id,
            )
        )
        if for_update:
            statement = statement.with_for_update(of=MemoryItemRow)
        result = (await self.session.execute(statement)).first()
        return item_model(*result) if result else None

    async def get_item_owner(self, memory_id: UUID, *, tenant_id: str) -> str | None:
        return await self.session.scalar(
            select(MemoryItemRow.user_id).where(
                MemoryItemRow.memory_id == memory_id,
                MemoryItemRow.tenant_id == tenant_id,
            )
        )

    async def list_active_items(
        self,
        *,
        tenant_id: str,
        user_id: str,
        memory_type: str | None = None,
    ) -> list[MemoryItem]:
        now = utc_now()
        statement = (
            select(MemoryItemRow, MemoryVersionRow)
            .join(
                MemoryVersionRow,
                and_(
                    MemoryVersionRow.memory_id == MemoryItemRow.memory_id,
                    MemoryVersionRow.version_id == MemoryItemRow.current_version_id,
                ),
            )
            .where(
                MemoryItemRow.tenant_id == tenant_id,
                MemoryItemRow.user_id == user_id,
                MemoryItemRow.status == MemoryStatus.ACTIVE.value,
                or_(MemoryItemRow.expires_at.is_(None), MemoryItemRow.expires_at > now),
            )
        )
        if memory_type:
            statement = statement.where(MemoryItemRow.memory_type == memory_type)
        rows = (await self.session.execute(statement)).all()
        return [item_model(row, version) for row, version in rows]

    async def create_item(
        self,
        candidate: MemoryCandidateCreate,
        *,
        canonical_key: str | None,
        actor: MemoryActor,
    ) -> MemoryItem:
        now = utc_now()
        item_row = MemoryItemRow(
            memory_id=uuid4(),
            tenant_id=candidate.tenant_id,
            user_id=candidate.user_id,
            memory_type=candidate.memory_type.value,
            scope=MemoryScope.GLOBAL_USER.value,
            status=MemoryStatus.ARCHIVED.value,
            canonical_key=canonical_key,
            importance=float(candidate.content_json.get("importance", 0.5)),
            confidence=candidate.confidence,
            effective_at=now,
            created_at=now,
            updated_at=now,
        )
        self.session.add(item_row)
        await self.session.flush()
        version_row = MemoryVersionRow(
            version_id=uuid4(),
            memory_id=item_row.memory_id,
            version_no=1,
            content_text=candidate.content_text,
            content_json=candidate.content_json,
            language=candidate.content_json.get("language"),
            relationship_stage=candidate.content_json.get("relationship_stage"),
            change_reason="candidate_approved",
            provenance={
                **candidate.provenance,
                "source_kind": candidate.source_kind.value,
                "source_client_id": candidate.source_client_id,
                "source_session_id": candidate.source_session_id,
                "source_turn_id": candidate.source_turn_id,
                "source_message_ids": candidate.source_message_ids,
            },
            created_by_type=actor.actor_type.value,
            created_by_id=actor.actor_id,
            created_at=now,
        )
        self.session.add(version_row)
        await self.session.flush()
        item_row.current_version_id = version_row.version_id
        item_row.status = MemoryStatus.ACTIVE.value
        await self.enqueue_embedding(version_row.version_id, candidate.tenant_id)
        await self.session.flush()
        return item_model(item_row, version_row)

    async def supersede_item(
        self,
        current: MemoryItem,
        update: MemoryUpdate,
        *,
        actor: MemoryActor,
    ) -> MemoryItem:
        locked = await self.session.scalar(
            select(MemoryItemRow)
            .where(MemoryItemRow.memory_id == current.memory_id)
            .with_for_update()
        )
        if locked is None:
            raise KeyError(str(current.memory_id))
        previous = await self.session.scalar(
            select(MemoryVersionRow).where(
                MemoryVersionRow.version_id == locked.current_version_id
            )
        )
        if previous is None:
            raise RuntimeError("current memory version is missing")
        now = utc_now()
        version_row = MemoryVersionRow(
            version_id=uuid4(),
            memory_id=locked.memory_id,
            version_no=previous.version_no + 1,
            content_text=update.content_text,
            content_json=update.content_json,
            language=update.content_json.get("language"),
            relationship_stage=update.relationship_stage,
            supersedes_version_id=previous.version_id,
            change_reason=update.change_reason,
            provenance=update.provenance,
            created_by_type=actor.actor_type.value,
            created_by_id=actor.actor_id,
            created_at=now,
        )
        self.session.add(version_row)
        await self.session.flush()
        locked.current_version_id = version_row.version_id
        locked.status = MemoryStatus.ACTIVE.value
        locked.confidence = update.confidence if update.confidence is not None else locked.confidence
        locked.importance = update.importance if update.importance is not None else locked.importance
        locked.effective_at = update.effective_at or locked.effective_at
        locked.expires_at = update.expires_at
        locked.deleted_at = None
        locked.updated_at = now
        await self.enqueue_embedding(version_row.version_id, update.tenant_id)
        await self.session.flush()
        return item_model(locked, version_row)

    async def set_item_status(
        self,
        item: MemoryItem,
        target: MemoryStatus,
    ) -> MemoryItem:
        row = await self.session.scalar(
            select(MemoryItemRow)
            .where(MemoryItemRow.memory_id == item.memory_id)
            .with_for_update()
        )
        if row is None:
            raise KeyError(str(item.memory_id))
        row.status = target.value
        row.deleted_at = utc_now() if target is MemoryStatus.DELETED else None
        row.updated_at = utc_now()
        version = await self.session.scalar(
            select(MemoryVersionRow).where(MemoryVersionRow.version_id == row.current_version_id)
        )
        await self.session.flush()
        if version is None:
            raise RuntimeError("current memory version is missing")
        return item_model(row, version)

    async def list_user_items(
        self, *, tenant_id: str, user_id: str, include_deleted: bool = True
    ) -> list[MemoryItem]:
        statement = (
            select(MemoryItemRow, MemoryVersionRow)
            .join(MemoryVersionRow, MemoryVersionRow.version_id == MemoryItemRow.current_version_id)
            .where(MemoryItemRow.tenant_id == tenant_id, MemoryItemRow.user_id == user_id)
            .order_by(MemoryItemRow.updated_at.desc())
        )
        if not include_deleted:
            statement = statement.where(MemoryItemRow.status != MemoryStatus.DELETED.value)
        rows = (await self.session.execute(statement)).all()
        return [item_model(row, version) for row, version in rows]

    async def enqueue_embedding(self, version_id: UUID, tenant_id: str) -> None:
        self.session.add(
            MemoryOutboxRow(
                outbox_id=uuid4(),
                event_type="embedding.requested",
                aggregate_id=version_id,
                payload={"tenant_id": tenant_id, "version_id": str(version_id)},
                status=OutboxStatus.PENDING.value,
                attempts=0,
                available_at=utc_now(),
            )
        )

    async def claim_outbox(
        self,
        *,
        worker_id: str,
        limit: int,
        lease_seconds: int,
    ) -> list[MemoryOutboxRow]:
        now = utc_now()
        stale_before = now - timedelta(seconds=lease_seconds)
        rows = list(
            (
                await self.session.scalars(
                    select(MemoryOutboxRow)
                    .where(
                        MemoryOutboxRow.event_type == "embedding.requested",
                        MemoryOutboxRow.available_at <= now,
                        or_(
                            MemoryOutboxRow.status.in_(
                                [OutboxStatus.PENDING.value, OutboxStatus.FAILED.value]
                            ),
                            and_(
                                MemoryOutboxRow.status == OutboxStatus.PROCESSING.value,
                                MemoryOutboxRow.locked_at < stale_before,
                            ),
                        ),
                    )
                    .order_by(MemoryOutboxRow.available_at, MemoryOutboxRow.created_at)
                    .with_for_update(skip_locked=True)
                    .limit(limit)
                )
            ).all()
        )
        for row in rows:
            row.status = OutboxStatus.PROCESSING.value
            row.locked_at = now
            row.locked_by = worker_id
        await self.session.flush()
        return rows

    async def get_version(self, version_id: UUID) -> MemoryVersionRow | None:
        return await self.session.scalar(
            select(MemoryVersionRow).where(MemoryVersionRow.version_id == version_id)
        )

    async def save_embedding(
        self,
        *,
        version_id: UUID,
        model: str,
        revision: str,
        vector: list[float],
        content_sha256: str,
    ) -> None:
        existing = await self.session.scalar(
            select(MemoryEmbeddingRow).where(
                MemoryEmbeddingRow.version_id == version_id,
                MemoryEmbeddingRow.embedding_model == model,
                MemoryEmbeddingRow.embedding_revision == revision,
            )
        )
        if existing:
            if existing.content_sha256 != content_sha256:
                existing.status = EmbeddingStatus.STALE.value
                raise RuntimeError("embedding content hash mismatch")
            existing.embedding = vector
            existing.status = EmbeddingStatus.READY.value
            return
        self.session.add(
            MemoryEmbeddingRow(
                embedding_id=uuid4(),
                version_id=version_id,
                embedding_model=model,
                embedding_revision=revision,
                embedding_dimension=len(vector),
                embedding=vector,
                content_sha256=content_sha256,
                status=EmbeddingStatus.READY.value,
            )
        )

    async def complete_outbox(self, outbox_id: UUID) -> None:
        row = await self.session.scalar(
            select(MemoryOutboxRow).where(MemoryOutboxRow.outbox_id == outbox_id).with_for_update()
        )
        if row:
            row.status = OutboxStatus.COMPLETED.value
            row.completed_at = utc_now()
            row.locked_at = None
            row.locked_by = None
            row.last_error = None

    async def fail_outbox(
        self,
        outbox_id: UUID,
        *,
        error_code: str,
        max_attempts: int,
        retry_delay_seconds: int,
    ) -> None:
        row = await self.session.scalar(
            select(MemoryOutboxRow).where(MemoryOutboxRow.outbox_id == outbox_id).with_for_update()
        )
        if row is None:
            return
        row.attempts += 1
        row.status = (
            OutboxStatus.DEAD_LETTER.value
            if row.attempts >= max_attempts
            else OutboxStatus.FAILED.value
        )
        row.available_at = utc_now() + timedelta(seconds=retry_delay_seconds)
        row.last_error = error_code[:200]
        row.locked_at = None
        row.locked_by = None

    async def vector_search(self, query: MemorySearchQuery) -> list[RetrievedItem]:
        if query.query_embedding is None:
            return []
        distance = MemoryEmbeddingRow.embedding.cosine_distance(query.query_embedding)
        statement = (
            select(MemoryItemRow, MemoryVersionRow, (1 - distance).label("semantic"))
            .join(
                MemoryVersionRow,
                and_(
                    MemoryVersionRow.memory_id == MemoryItemRow.memory_id,
                    MemoryVersionRow.version_id == MemoryItemRow.current_version_id,
                ),
            )
            .join(
                MemoryEmbeddingRow,
                and_(
                    MemoryEmbeddingRow.version_id == MemoryVersionRow.version_id,
                    MemoryEmbeddingRow.status == EmbeddingStatus.READY.value,
                ),
            )
            .where(
                MemoryItemRow.tenant_id == query.tenant_id,
                MemoryItemRow.user_id == query.user_id,
                MemoryItemRow.status == MemoryStatus.ACTIVE.value,
                MemoryItemRow.scope.in_([scope.value for scope in query.scopes]),
                or_(MemoryItemRow.expires_at.is_(None), MemoryItemRow.expires_at > query.now),
            )
            .order_by(distance)
            .limit(query.limit * 3)
        )
        if query.memory_types:
            statement = statement.where(
                MemoryItemRow.memory_type.in_([kind.value for kind in query.memory_types])
            )
        if query.embedding_model:
            statement = statement.where(
                MemoryEmbeddingRow.embedding_model == query.embedding_model
            )
        if query.embedding_revision:
            statement = statement.where(
                MemoryEmbeddingRow.embedding_revision == query.embedding_revision
            )
        rows = (await self.session.execute(statement)).all()
        return [RetrievedItem(item_model(row, version), semantic=float(score)) for row, version, score in rows]

    async def keyword_search(self, query: MemorySearchQuery) -> list[RetrievedItem]:
        document = func.to_tsvector("simple", MemoryVersionRow.content_text)
        ts_query = func.plainto_tsquery("simple", query.query)
        rank = func.ts_rank_cd(document, ts_query)
        statement = (
            select(MemoryItemRow, MemoryVersionRow, rank.label("keyword"))
            .join(
                MemoryVersionRow,
                and_(
                    MemoryVersionRow.memory_id == MemoryItemRow.memory_id,
                    MemoryVersionRow.version_id == MemoryItemRow.current_version_id,
                ),
            )
            .where(
                MemoryItemRow.tenant_id == query.tenant_id,
                MemoryItemRow.user_id == query.user_id,
                MemoryItemRow.status == MemoryStatus.ACTIVE.value,
                MemoryItemRow.scope.in_([scope.value for scope in query.scopes]),
                or_(MemoryItemRow.expires_at.is_(None), MemoryItemRow.expires_at > query.now),
                or_(
                    document.op("@@")(ts_query),
                    MemoryVersionRow.content_text.contains(query.query, autoescape=True),
                ),
            )
            .order_by(rank.desc())
            .limit(query.limit * 3)
        )
        if query.memory_types:
            statement = statement.where(
                MemoryItemRow.memory_type.in_([kind.value for kind in query.memory_types])
            )
        rows = (await self.session.execute(statement)).all()
        return [
            RetrievedItem(item_model(row, version), keyword=min(1.0, float(score)))
            for row, version, score in rows
        ]

    async def bind_identity(
        self, binding: IdentityBindingCreate
    ) -> IdentityBinding:
        now = utc_now()
        row = IdentityBindingRow(
            binding_id=uuid4(),
            **binding.model_dump(),
            status=IdentityBindingStatus.ACTIVE.value,
            verified_at=now,
            created_at=now,
            updated_at=now,
        )
        self.session.add(row)
        await self.session.flush()
        return binding_model(row)

    async def get_binding_for_update(self, binding_id: UUID) -> IdentityBindingRow | None:
        return await self.session.scalar(
            select(IdentityBindingRow)
            .where(IdentityBindingRow.binding_id == binding_id)
            .with_for_update()
        )

    async def unbind_identity(self, row: IdentityBindingRow) -> None:
        row.status = IdentityBindingStatus.UNBOUND.value
        row.updated_at = utc_now()
        await self.session.flush()

    async def resolve_identity(
        self, *, tenant_id: str, platform: str, platform_user_id: str
    ) -> str | None:
        return await self.session.scalar(
            select(IdentityBindingRow.user_id).where(
                IdentityBindingRow.tenant_id == tenant_id,
                IdentityBindingRow.platform == platform,
                IdentityBindingRow.platform_user_id == platform_user_id,
                IdentityBindingRow.status == IdentityBindingStatus.ACTIVE.value,
            )
        )

    async def upsert_session_summary(self, summary: SessionSummaryUpsert) -> SessionSummaryUpsert:
        row = await self.session.scalar(
            select(SessionSummaryRow)
            .where(
                SessionSummaryRow.tenant_id == summary.tenant_id,
                SessionSummaryRow.user_id == summary.user_id,
                SessionSummaryRow.client_id == summary.client_id,
                SessionSummaryRow.session_id == summary.session_id,
            )
            .with_for_update()
        )
        if row is None:
            self.session.add(
                SessionSummaryRow(
                    summary_id=uuid4(),
                    **summary.model_dump(),
                    version=1,
                )
            )
        else:
            row.summary_text = summary.summary_text
            row.summary_json = summary.summary_json
            row.source_range = summary.source_range
            row.version += 1
            row.updated_at = utc_now()
        await self.session.flush()
        return summary

    async def list_audit_events(self, *, tenant_id: str, user_id: str) -> list[dict[str, Any]]:
        rows = list(
            (
                await self.session.scalars(
                    select(MemoryAuditLogRow)
                    .where(
                        MemoryAuditLogRow.tenant_id == tenant_id,
                        MemoryAuditLogRow.details["user_id"].astext == user_id,
                    )
                    .order_by(MemoryAuditLogRow.audit_id)
                )
            ).all()
        )
        return [
            {
                "audit_id": row.audit_id,
                "request_id": row.request_id,
                "action": row.action,
                "aggregate_type": row.aggregate_type,
                "aggregate_id": row.aggregate_id,
                "actor_type": row.actor_type,
                "actor_id": row.actor_id,
                "details": row.details,
                "created_at": row.created_at.isoformat(),
            }
            for row in rows
        ]


class MemoryUnitOfWork:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self.session_factory = session_factory
        self.session: AsyncSession | None = None
        self.repository: SqlAlchemyMemoryRepository | None = None
        self._transaction = None

    async def __aenter__(self) -> "MemoryUnitOfWork":
        self.session = self.session_factory()
        self._transaction = self.session.begin()
        await self._transaction.__aenter__()
        self.repository = SqlAlchemyMemoryRepository(self.session)
        return self

    async def __aexit__(self, exc_type, exc, traceback) -> None:
        assert self.session is not None and self._transaction is not None
        try:
            await self._transaction.__aexit__(exc_type, exc, traceback)
        finally:
            await self.session.close()


class MemoryUnitOfWorkFactory:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self.session_factory = session_factory

    def __call__(self) -> MemoryUnitOfWork:
        return MemoryUnitOfWork(self.session_factory)
