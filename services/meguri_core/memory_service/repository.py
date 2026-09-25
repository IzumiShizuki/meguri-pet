from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import Text, and_, case, cast, delete, exists, func, or_, select, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.exc import IntegrityError

from .contracts import MemoryConflictError

from .enums import (
    AuditAction,
    CandidateStatus,
    EmbeddingStatus,
    IdentityBindingStatus,
    MemoryScope,
    MemoryStatus,
    MemoryVersionStatus,
    MergePolicy,
    OutboxStatus,
    candidate_transition_allowed,
    memory_transition_allowed,
)
from .models import (
    IdentityBinding,
    IdentityBindingCreate,
    MemoryActor,
    MemoryCandidate,
    MemoryCandidateCreate,
    MemoryFeedback,
    MemoryFeedbackCreate,
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
    MemoryFeedbackRow,
    MemoryIdempotencyRow,
    MemoryItemRow,
    MemoryOutboxRow,
    MemoryVersionRow,
    SessionSummaryRow,
)
from .review_policy import CandidateReviewPolicy, is_redacted_candidate
from .merge import MemoryMergeEngine, is_ancestor_version


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _approved_item_filter():
    return exists(
        select(MemoryCandidateRow.candidate_id).where(
            MemoryCandidateRow.accepted_memory_id == MemoryItemRow.memory_id,
            MemoryCandidateRow.status.in_(
                [CandidateStatus.APPROVED.value, CandidateStatus.AUTO_APPROVED.value]
            ),
        )
    )


def _retrieval_version_id():
    return case(
        (
            MemoryItemRow.status == MemoryStatus.CONFLICTED.value,
            MemoryItemRow.last_stable_version_id,
        ),
        else_=MemoryItemRow.current_version_id,
    )


def _retrievable_item_statuses() -> list[str]:
    return [MemoryStatus.ACTIVE.value, MemoryStatus.CONFLICTED.value]


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
        risk_level=row.risk_level,
        merge_policy=row.merge_policy,
        base_version_id=row.base_version_id,
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
        status=row.status,
        base_version_id=row.base_version_id,
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
        last_stable_version_id=row.last_stable_version_id,
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


def feedback_model(row: MemoryFeedbackRow) -> MemoryFeedback:
    return MemoryFeedback(
        feedback_id=row.feedback_id,
        tenant_id=row.tenant_id,
        user_id=row.user_id,
        memory_id=row.memory_id,
        version_id=row.version_id,
        feedback_kind=row.feedback_kind,
        query_text=row.query_text,
        hit_rank=row.hit_rank,
        details=row.details,
        created_at=row.created_at,
    )


class SqlAlchemyMemoryRepository:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def lock_idempotency_key(
        self, tenant_id: str, operation: str, request_id: str
    ) -> None:
        # PostgreSQL text values cannot contain NUL bytes.  A compact JSON array
        # keeps the three lock-key components unambiguous while remaining valid
        # UTF-8 text even when a component itself contains a separator character.
        key = json.dumps(
            (tenant_id, operation, request_id),
            ensure_ascii=True,
            separators=(",", ":"),
        )
        await self.session.execute(
            text("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))"),
            {"key": key},
        )

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
        if status not in {
            CandidateStatus.PENDING,
            CandidateStatus.PENDING_REVIEW,
            CandidateStatus.NEEDS_CONFIRMATION,
            CandidateStatus.REJECTED,
        }:
            raise MemoryConflictError(
                f"candidate cannot be created directly in state: {status.value}"
            )
        evaluation = CandidateReviewPolicy().evaluate(candidate)
        if evaluation.rejected and not is_redacted_candidate(candidate):
            raise ValueError(
                "rejected candidate content must be redacted before persistence"
            )
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

    async def list_candidates(
        self,
        *,
        tenant_id: str,
        user_id: str,
        status: str | None = None,
    ) -> list[MemoryCandidate]:
        statement = (
            select(MemoryCandidateRow)
            .where(
                MemoryCandidateRow.tenant_id == tenant_id,
                MemoryCandidateRow.user_id == user_id,
            )
            .order_by(MemoryCandidateRow.created_at.desc())
        )
        if status:
            statement = statement.where(MemoryCandidateRow.status == status)
        rows = list((await self.session.scalars(statement)).all())
        return [candidate_model(row) for row in rows]

    async def finish_candidate(
        self,
        row: MemoryCandidateRow,
        *,
        status: CandidateStatus,
        actor: MemoryActor,
        reason: str,
        accepted_memory_id: UUID | None = None,
    ) -> MemoryCandidate:
        current = CandidateStatus(row.status)
        if not candidate_transition_allowed(current, status):
            raise MemoryConflictError(
                f"illegal candidate transition: {current.value} -> {status.value}"
            )
        if status is CandidateStatus.APPROVED and accepted_memory_id is None:
            raise MemoryConflictError("approved candidate requires an accepted memory")
        if status in {CandidateStatus.REJECTED, CandidateStatus.EXPIRED}:
            accepted_memory_id = None
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

    async def get_item_by_version(
        self, version_id: UUID, *, tenant_id: str, user_id: str
    ) -> MemoryItem | None:
        memory_id = await self.session.scalar(
            select(MemoryVersionRow.memory_id)
            .join(MemoryItemRow, MemoryItemRow.memory_id == MemoryVersionRow.memory_id)
            .where(
                MemoryVersionRow.version_id == version_id,
                MemoryItemRow.tenant_id == tenant_id,
                MemoryItemRow.user_id == user_id,
            )
        )
        if memory_id is None:
            return None
        return await self.get_item(memory_id, tenant_id=tenant_id, user_id=user_id)

    async def create_feedback(
        self, feedback: MemoryFeedbackCreate
    ) -> MemoryFeedback | None:
        valid_version = await self.session.scalar(
            select(MemoryVersionRow.version_id)
            .join(
                MemoryItemRow,
                MemoryItemRow.memory_id == MemoryVersionRow.memory_id,
            )
            .where(
                MemoryItemRow.memory_id == feedback.memory_id,
                MemoryItemRow.tenant_id == feedback.tenant_id,
                MemoryItemRow.user_id == feedback.user_id,
                MemoryVersionRow.version_id == feedback.version_id,
                MemoryVersionRow.memory_id == feedback.memory_id,
            )
        )
        if valid_version is None:
            return None
        payload = feedback.model_dump()
        payload["feedback_kind"] = feedback.feedback_kind.value
        row = MemoryFeedbackRow(feedback_id=uuid4(), **payload)
        self.session.add(row)
        await self.session.flush()
        return feedback_model(row)

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
                    MemoryVersionRow.version_id == _retrieval_version_id(),
                ),
            )
            .where(
                MemoryItemRow.tenant_id == tenant_id,
                MemoryItemRow.user_id == user_id,
                MemoryItemRow.status.in_(_retrievable_item_statuses()),
                MemoryVersionRow.status == MemoryVersionStatus.ACTIVE.value,
                _approved_item_filter(),
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
            status=MemoryVersionStatus.ACTIVE.value,
            base_version_id=None,
            content_text=candidate.content_text,
            content_json=candidate.content_json,
            language=candidate.content_json.get("language"),
            relationship_stage=None,
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
        item_row.last_stable_version_id = version_row.version_id
        item_row.status = MemoryStatus.ACTIVE.value
        await self.enqueue_embedding(version_row.version_id, candidate.tenant_id)
        await self.enqueue_file_mirror(item_row.memory_id, version_row.version_id, candidate.tenant_id)
        try:
            await self.session.flush()
        except IntegrityError as exc:
            raise MemoryConflictError(
                "an active memory with the same canonical key already exists"
            ) from exc
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
        if locked.status != MemoryStatus.ACTIVE.value:
            raise MemoryConflictError("only active memory can be superseded")
        previous = await self.session.scalar(
            select(MemoryVersionRow).where(
                MemoryVersionRow.version_id == locked.current_version_id
            )
        )
        if previous is None:
            raise RuntimeError("current memory version is missing")
        if previous.status != MemoryVersionStatus.ACTIVE.value:
            raise MemoryConflictError("current memory version is not active")
        if locked.last_stable_version_id != previous.version_id:
            raise MemoryConflictError("current memory is not the last stable version")
        if update.base_version_id is None:
            raise MemoryConflictError("base_version_id is required for supersede")
        if update.base_version_id != previous.version_id:
            raise MemoryConflictError("memory base version changed before supersede")
        now = utc_now()
        version_row = MemoryVersionRow(
            version_id=uuid4(),
            memory_id=locked.memory_id,
            version_no=previous.version_no + 1,
            status=MemoryVersionStatus.ACTIVE.value,
            base_version_id=update.base_version_id,
            content_text=update.content_text,
            content_json=update.content_json,
            language=update.content_json.get("language"),
            relationship_stage=previous.relationship_stage,
            supersedes_version_id=previous.version_id,
            change_reason=update.change_reason,
            provenance=update.provenance,
            created_by_type=actor.actor_type.value,
            created_by_id=actor.actor_id,
            created_at=now,
        )
        self.session.add(version_row)
        await self.session.flush()
        previous.status = MemoryVersionStatus.SUPERSEDED.value
        locked.current_version_id = version_row.version_id
        locked.last_stable_version_id = version_row.version_id
        locked.status = MemoryStatus.ACTIVE.value
        locked.confidence = update.confidence if update.confidence is not None else locked.confidence
        locked.importance = update.importance if update.importance is not None else locked.importance
        locked.effective_at = update.effective_at or locked.effective_at
        locked.expires_at = update.expires_at
        locked.deleted_at = None
        locked.updated_at = now
        await self.enqueue_embedding(version_row.version_id, update.tenant_id)
        await self.enqueue_file_mirror(locked.memory_id, version_row.version_id, update.tenant_id)
        await self.session.flush()
        return item_model(locked, version_row)

    async def merge_candidate(
        self,
        current: MemoryItem,
        candidate: MemoryCandidateCreate,
        *,
        actor: MemoryActor,
        reason: str,
    ) -> MemoryItem:
        locked = await self.session.scalar(
            select(MemoryItemRow).where(MemoryItemRow.memory_id == current.memory_id).with_for_update()
        )
        if locked is None:
            raise KeyError(str(current.memory_id))
        if locked.status != MemoryStatus.ACTIVE.value:
            raise MemoryConflictError(
                "conflicted or non-active memory requires explicit conflict resolution"
            )
        active = await self.session.scalar(
            select(MemoryVersionRow).where(MemoryVersionRow.version_id == locked.current_version_id)
        )
        if active is None:
            raise RuntimeError("current memory version is missing")
        if active.status != MemoryVersionStatus.ACTIVE.value:
            raise MemoryConflictError("current memory version is not active")
        if locked.last_stable_version_id != active.version_id:
            raise MemoryConflictError("current memory is not the last stable version")
        if candidate.base_version_id is None:
            raise MemoryConflictError(
                f"{candidate.merge_policy.value} requires base_version_id"
            )
        base = await self.session.scalar(
            select(MemoryVersionRow).where(
                MemoryVersionRow.version_id == candidate.base_version_id,
                MemoryVersionRow.memory_id == locked.memory_id,
            )
        )
        if base is None:
            raise MemoryConflictError("candidate base version does not belong to the memory")
        if base.status in {
            MemoryVersionStatus.CONFLICT_BRANCH.value,
            MemoryVersionStatus.TOMBSTONE.value,
        }:
            raise MemoryConflictError("candidate base must be a stable memory version")
        if candidate.merge_policy in {
            MergePolicy.THREE_WAY_MERGE,
            MergePolicy.MANUAL,
        }:
            versions = list(
                (
                    await self.session.scalars(
                        select(MemoryVersionRow).where(
                            MemoryVersionRow.memory_id == locked.memory_id
                        )
                    )
                ).all()
            )
            parent_by_version = {
                version.version_id: version.supersedes_version_id for version in versions
            }
            if not is_ancestor_version(
                base.version_id, active.version_id, parent_by_version
            ):
                raise MemoryConflictError(
                    "candidate base is not an ancestor of the current stable version"
                )
        elif base.version_id != active.version_id:
            raise MemoryConflictError("memory base version changed before merge")
        result = MemoryMergeEngine().merge(
            candidate.merge_policy,
            current=active.content_json,
            proposed=candidate.content_json,
            base=base.content_json if base is not None else None,
        )
        if candidate.merge_policy is MergePolicy.CONFIRM and not result.conflicted:
            return item_model(locked, active)
        now = utc_now()
        next_number = int(
            await self.session.scalar(
                select(func.coalesce(func.max(MemoryVersionRow.version_no), 0)).where(
                    MemoryVersionRow.memory_id == locked.memory_id
                )
            )
            or 0
        ) + 1
        branch = result.conflicted
        version = MemoryVersionRow(
            version_id=uuid4(), memory_id=locked.memory_id, version_no=next_number,
            status=(MemoryVersionStatus.CONFLICT_BRANCH.value if branch else MemoryVersionStatus.ACTIVE.value),
            base_version_id=candidate.base_version_id,
            content_text=candidate.content_text,
            content_json=(candidate.content_json if branch else result.content_json),
            language=candidate.content_json.get("language"),
            relationship_stage=active.relationship_stage,
            supersedes_version_id=None if branch else active.version_id,
            change_reason=reason,
            provenance={**candidate.provenance, "conflicted_fields": sorted(result.conflicted_fields)},
            created_by_type=actor.actor_type.value, created_by_id=actor.actor_id, created_at=now,
        )
        self.session.add(version)
        await self.session.flush()
        if branch:
            locked.status = MemoryStatus.CONFLICTED.value
            locked.last_stable_version_id = locked.last_stable_version_id or active.version_id
            await self.enqueue_projection(
                "conflict.notification", locked.memory_id,
                {"tenant_id": candidate.tenant_id, "memory_id": str(locked.memory_id),
                 "branch_version_id": str(version.version_id),
                 "last_stable_version_id": str(locked.last_stable_version_id)},
            )
            locked.updated_at = now
            await self.session.flush()
            return item_model(locked, active)
        active.status = MemoryVersionStatus.SUPERSEDED.value
        locked.current_version_id = version.version_id
        locked.last_stable_version_id = version.version_id
        locked.status = MemoryStatus.ACTIVE.value
        locked.updated_at = now
        await self.enqueue_embedding(version.version_id, candidate.tenant_id)
        await self.enqueue_file_mirror(locked.memory_id, version.version_id, candidate.tenant_id)
        await self.session.flush()
        return item_model(locked, version)

    async def tombstone_item(self, item: MemoryItem, *, actor: MemoryActor, reason: str) -> MemoryItem:
        locked = await self.session.scalar(
            select(MemoryItemRow).where(MemoryItemRow.memory_id == item.memory_id).with_for_update()
        )
        if locked is None:
            raise KeyError(str(item.memory_id))
        current = await self.session.scalar(
            select(MemoryVersionRow).where(MemoryVersionRow.version_id == locked.current_version_id)
        )
        if current is None:
            raise RuntimeError("current memory version is missing")
        now = utc_now()
        tombstone = MemoryVersionRow(
            version_id=uuid4(), memory_id=locked.memory_id, version_no=current.version_no + 1,
            status=MemoryVersionStatus.TOMBSTONE.value, base_version_id=current.version_id,
            content_text="[deleted]", content_json={}, supersedes_version_id=current.version_id,
            change_reason=reason, provenance={"deletion": True},
            created_by_type=actor.actor_type.value, created_by_id=actor.actor_id, created_at=now,
        )
        self.session.add(tombstone)
        await self.session.flush()
        current.status = MemoryVersionStatus.SUPERSEDED.value
        locked.current_version_id = tombstone.version_id
        locked.status = MemoryStatus.DELETED.value
        locked.deleted_at = locked.updated_at = now
        deletion = {
            "tenant_id": item.tenant_id,
            "memory_id": str(item.memory_id),
            "version_id": str(tombstone.version_id),
            "deleted_version_id": str(current.version_id),
        }
        await self.enqueue_projection("embedding.deleted", current.version_id, deletion)
        await self.enqueue_projection("file_mirror.deleted", tombstone.version_id, deletion)
        await self.session.flush()
        return item_model(locked, tombstone)

    async def restore_item(self, item: MemoryItem, *, actor: MemoryActor, reason: str) -> MemoryItem:
        locked = await self.session.scalar(
            select(MemoryItemRow).where(MemoryItemRow.memory_id == item.memory_id).with_for_update()
        )
        if locked is None or locked.status != MemoryStatus.DELETED.value:
            raise MemoryConflictError("only a deleted memory can be restored")
        tombstone = await self.session.scalar(
            select(MemoryVersionRow).where(MemoryVersionRow.version_id == locked.current_version_id)
        )
        stable = await self.session.scalar(
            select(MemoryVersionRow).where(MemoryVersionRow.version_id == locked.last_stable_version_id)
        )
        if tombstone is None or stable is None:
            raise RuntimeError("deleted memory has no stable version to restore")
        if tombstone.status != MemoryVersionStatus.TOMBSTONE.value:
            raise MemoryConflictError("deleted memory current version is not a tombstone")
        if stable.status != MemoryVersionStatus.SUPERSEDED.value:
            raise MemoryConflictError("deleted memory last stable version is invalid")
        now = utc_now()
        restored = MemoryVersionRow(
            version_id=uuid4(), memory_id=locked.memory_id, version_no=tombstone.version_no + 1,
            status=MemoryVersionStatus.ACTIVE.value, base_version_id=tombstone.version_id,
            content_text=stable.content_text, content_json=stable.content_json,
            language=stable.language, relationship_stage=stable.relationship_stage,
            supersedes_version_id=tombstone.version_id, change_reason=reason,
            provenance={"restored_from_version_id": str(stable.version_id)},
            created_by_type=actor.actor_type.value, created_by_id=actor.actor_id, created_at=now,
        )
        self.session.add(restored)
        await self.session.flush()
        locked.current_version_id = locked.last_stable_version_id = restored.version_id
        locked.status = MemoryStatus.ACTIVE.value
        locked.deleted_at = None
        locked.updated_at = now
        await self.enqueue_embedding(restored.version_id, item.tenant_id)
        await self.enqueue_file_mirror(item.memory_id, restored.version_id, item.tenant_id)
        await self.session.flush()
        return item_model(locked, restored)

    async def delete_embedding_projection(self, version_id: UUID) -> None:
        await self.session.execute(
            delete(MemoryEmbeddingRow).where(MemoryEmbeddingRow.version_id == version_id)
        )

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
        current = MemoryStatus(row.status)
        if not memory_transition_allowed(current, target):
            raise MemoryConflictError(
                f"illegal memory transition: {current.value} -> {target.value}"
            )
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

    async def list_user_versions(
        self, *, tenant_id: str, user_id: str
    ) -> list[MemoryVersion]:
        rows = list(
            (
                await self.session.scalars(
                    select(MemoryVersionRow)
                    .join(
                        MemoryItemRow,
                        MemoryItemRow.memory_id == MemoryVersionRow.memory_id,
                    )
                    .where(
                        MemoryItemRow.tenant_id == tenant_id,
                        MemoryItemRow.user_id == user_id,
                    )
                    .order_by(
                        MemoryVersionRow.memory_id,
                        MemoryVersionRow.version_no,
                    )
                )
            ).all()
        )
        return [version_model(row) for row in rows]

    async def hard_delete_item(self, item: MemoryItem) -> dict[str, int]:
        """Physically remove one soft-deleted aggregate while retaining audit rows."""

        version_ids = list(
            (
                await self.session.scalars(
                    select(MemoryVersionRow.version_id).where(
                        MemoryVersionRow.memory_id == item.memory_id
                    )
                )
            ).all()
        )
        candidate_ids = list(
            (
                await self.session.scalars(
                    select(MemoryCandidateRow.candidate_id).where(
                        MemoryCandidateRow.accepted_memory_id == item.memory_id
                    )
                )
            ).all()
        )
        if version_ids:
            await self.session.execute(
                delete(MemoryOutboxRow).where(
                    MemoryOutboxRow.aggregate_id.in_(version_ids)
                )
            )
        aggregate_identifiers = [
            str(item.memory_id),
            *(str(candidate_id) for candidate_id in candidate_ids),
        ]
        await self.session.execute(
            delete(MemoryIdempotencyRow).where(
                MemoryIdempotencyRow.tenant_id == item.tenant_id,
                or_(
                    *(
                        MemoryIdempotencyRow.operation.contains(identifier)
                        for identifier in aggregate_identifiers
                    ),
                    *(
                        cast(MemoryIdempotencyRow.response_json, Text).contains(
                            identifier
                        )
                        for identifier in aggregate_identifiers
                    ),
                ),
            )
        )
        await self.session.execute(
            delete(MemoryCandidateRow).where(
                MemoryCandidateRow.accepted_memory_id == item.memory_id
            )
        )
        await self.session.execute(
            delete(MemoryItemRow).where(
                MemoryItemRow.memory_id == item.memory_id,
                MemoryItemRow.tenant_id == item.tenant_id,
                MemoryItemRow.user_id == item.user_id,
            )
        )
        await self.session.flush()
        return {
            "deleted_versions": len(version_ids),
            "deleted_candidates": len(candidate_ids),
        }

    async def enqueue_embedding(self, version_id: UUID, tenant_id: str) -> None:
        await self.enqueue_projection(
            "embedding.requested", version_id,
            {"tenant_id": tenant_id, "version_id": str(version_id)},
        )

    async def enqueue_file_mirror(
        self, memory_id: UUID, version_id: UUID, tenant_id: str
    ) -> None:
        await self.enqueue_projection(
            "file_mirror.requested", version_id,
            {"tenant_id": tenant_id, "memory_id": str(memory_id), "version_id": str(version_id)},
        )

    async def enqueue_projection(
        self, event_type: str, aggregate_id: UUID, payload: dict[str, Any]
    ) -> None:
        self.session.add(
            MemoryOutboxRow(
                outbox_id=uuid4(),
                event_type=event_type,
                aggregate_id=aggregate_id,
                payload=payload,
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
        event_types: tuple[str, ...] = ("embedding.requested",),
    ) -> list[MemoryOutboxRow]:
        now = utc_now()
        stale_before = now - timedelta(seconds=lease_seconds)
        rows = list(
            (
                await self.session.scalars(
                    select(MemoryOutboxRow)
                    .where(
                        MemoryOutboxRow.event_type.in_(event_types),
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

    async def complete_outbox(
        self,
        outbox_id: UUID,
        *,
        worker_id: str,
        claim_locked_at: datetime,
    ) -> bool:
        row = await self.session.scalar(
            select(MemoryOutboxRow).where(MemoryOutboxRow.outbox_id == outbox_id).with_for_update()
        )
        if not self._owns_outbox_claim(row, worker_id, claim_locked_at):
            return False
        row.status = OutboxStatus.COMPLETED.value
        row.completed_at = utc_now()
        row.locked_at = None
        row.locked_by = None
        row.last_error = None
        return True

    async def fail_outbox(
        self,
        outbox_id: UUID,
        *,
        worker_id: str,
        claim_locked_at: datetime,
        error_code: str,
        max_attempts: int,
        retry_delay_seconds: int,
    ) -> bool:
        row = await self.session.scalar(
            select(MemoryOutboxRow).where(MemoryOutboxRow.outbox_id == outbox_id).with_for_update()
        )
        if not self._owns_outbox_claim(row, worker_id, claim_locked_at):
            return False
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
        return True

    @staticmethod
    def _owns_outbox_claim(
        row: MemoryOutboxRow | None,
        worker_id: str,
        claim_locked_at: datetime,
    ) -> bool:
        return bool(
            row is not None
            and row.status == OutboxStatus.PROCESSING.value
            and row.locked_by == worker_id
            and row.locked_at == claim_locked_at
        )

    async def requeue_dead_letters(self, *, event_types: tuple[str, ...]) -> int:
        rows = list(
            (
                await self.session.scalars(
                    select(MemoryOutboxRow)
                    .where(
                        MemoryOutboxRow.event_type.in_(event_types),
                        MemoryOutboxRow.status == OutboxStatus.DEAD_LETTER.value,
                    )
                    .with_for_update(skip_locked=True)
                )
            ).all()
        )
        for row in rows:
            row.status = OutboxStatus.PENDING.value
            row.attempts = 0
            row.available_at = utc_now()
            row.locked_at = row.locked_by = row.last_error = None
        await self.session.flush()
        return len(rows)

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
                    MemoryVersionRow.version_id == _retrieval_version_id(),
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
                MemoryItemRow.status.in_(_retrievable_item_statuses()),
                MemoryVersionRow.status == MemoryVersionStatus.ACTIVE.value,
                _approved_item_filter(),
                MemoryItemRow.scope.in_([scope.value for scope in query.scopes]),
                or_(MemoryItemRow.effective_at.is_(None), MemoryItemRow.effective_at <= query.now),
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

    async def structured_search(self, query: MemorySearchQuery) -> list[RetrievedItem]:
        if query.canonical_key is None:
            return []
        statement = (
            select(MemoryItemRow, MemoryVersionRow)
            .join(
                MemoryVersionRow,
                and_(
                    MemoryVersionRow.memory_id == MemoryItemRow.memory_id,
                    MemoryVersionRow.version_id == _retrieval_version_id(),
                ),
            )
            .where(
                MemoryItemRow.tenant_id == query.tenant_id,
                MemoryItemRow.user_id == query.user_id,
                MemoryItemRow.status.in_(_retrievable_item_statuses()),
                MemoryVersionRow.status == MemoryVersionStatus.ACTIVE.value,
                _approved_item_filter(),
                MemoryItemRow.scope.in_([scope.value for scope in query.scopes]),
                or_(MemoryItemRow.effective_at.is_(None), MemoryItemRow.effective_at <= query.now),
                MemoryItemRow.canonical_key == query.canonical_key,
                or_(MemoryItemRow.expires_at.is_(None), MemoryItemRow.expires_at > query.now),
            )
            .limit(query.limit)
        )
        if query.memory_types:
            statement = statement.where(
                MemoryItemRow.memory_type.in_(
                    [kind.value for kind in query.memory_types]
                )
            )
        rows = (await self.session.execute(statement)).all()
        return [RetrievedItem(item_model(row, version), keyword=1.0) for row, version in rows]

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
                    MemoryVersionRow.version_id == _retrieval_version_id(),
                ),
            )
            .where(
                MemoryItemRow.tenant_id == query.tenant_id,
                MemoryItemRow.user_id == query.user_id,
                MemoryItemRow.status.in_(_retrievable_item_statuses()),
                MemoryVersionRow.status == MemoryVersionStatus.ACTIVE.value,
                _approved_item_filter(),
                MemoryItemRow.scope.in_([scope.value for scope in query.scopes]),
                or_(MemoryItemRow.effective_at.is_(None), MemoryItemRow.effective_at <= query.now),
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

    async def list_identity_bindings(
        self, *, tenant_id: str, user_id: str
    ) -> list[IdentityBinding]:
        rows = list(
            (
                await self.session.scalars(
                    select(IdentityBindingRow)
                    .where(
                        IdentityBindingRow.tenant_id == tenant_id,
                        IdentityBindingRow.user_id == user_id,
                    )
                    .order_by(IdentityBindingRow.created_at)
                )
            ).all()
        )
        return [binding_model(row) for row in rows]

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
