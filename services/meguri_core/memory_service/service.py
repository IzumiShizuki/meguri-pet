from __future__ import annotations

from typing import Any
from uuid import UUID

from .conflict_resolver import ConflictResolver, canonical_key
from .contracts import (
    MemoryNotFoundError,
    MemoryStateError,
)
from .enums import (
    ActorType,
    AuditAction,
    CandidateStatus,
    ConflictAction,
    MemoryStatus,
    ReviewDecision,
    SearchMode,
)
from .models import (
    CandidateReview,
    IdentityBinding,
    IdentityBindingCreate,
    MemoryActor,
    MemoryCandidate,
    MemoryCandidateCreate,
    MemoryExport,
    MemoryHit,
    MemoryItem,
    MemorySearchQuery,
    MemoryUpdate,
    SessionSummaryUpsert,
)
from .repository import (
    MemoryUnitOfWorkFactory,
    SqlAlchemyMemoryRepository,
    candidate_model,
)
from .retrieval import build_hit, rerank_and_budget
from .review_policy import CandidateReviewPolicy


def require_request_id(request_id: str) -> str:
    value = request_id.strip()
    if not value:
        raise ValueError("request_id must not be empty")
    if len(value) > 200:
        raise ValueError("request_id must be at most 200 characters")
    return value


def candidate_create_from_model(candidate: MemoryCandidate) -> MemoryCandidateCreate:
    return MemoryCandidateCreate.model_validate(
        candidate.model_dump(
            exclude={
                "candidate_id",
                "status",
                "review_reason",
                "reviewed_by",
                "reviewed_at",
                "accepted_memory_id",
                "created_at",
                "updated_at",
            }
        )
    )


def repository_of(unit_of_work) -> SqlAlchemyMemoryRepository:
    repository = unit_of_work.repository
    if repository is None:
        raise RuntimeError("memory unit of work is not active")
    return repository


class MemoryService:
    def __init__(
        self,
        unit_of_work_factory: MemoryUnitOfWorkFactory,
        *,
        review_policy: CandidateReviewPolicy | None = None,
        conflict_resolver: ConflictResolver | None = None,
    ) -> None:
        self.uow_factory = unit_of_work_factory
        self.review_policy = review_policy or CandidateReviewPolicy()
        self.conflict_resolver = conflict_resolver or ConflictResolver()

    async def create_candidate(
        self,
        candidate: MemoryCandidateCreate,
        *,
        request_id: str,
    ) -> MemoryCandidate:
        request_id = require_request_id(request_id)
        operation = "candidate.create"
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            cached = await repository.get_idempotent(candidate.tenant_id, operation, request_id)
            if cached:
                return MemoryCandidate.model_validate(cached)
            evaluation = self.review_policy.evaluate(candidate)
            status = (
                CandidateStatus.REJECTED
                if evaluation.rejected
                else CandidateStatus.PENDING_REVIEW
            )
            reason = evaluation.reason
            created = await repository.create_candidate(
                candidate,
                status=status,
                review_reason=reason if evaluation.rejected else None,
            )
            actor = MemoryActor(actor_type=ActorType.SYSTEM, actor_id="memory-service")
            await repository.append_audit(
                tenant_id=candidate.tenant_id,
                request_id=request_id,
                action=AuditAction.CANDIDATE_CREATE,
                aggregate_type="candidate",
                aggregate_id=str(created.candidate_id),
                actor=actor,
                details={
                    "user_id": candidate.user_id,
                    "status": created.status.value,
                    "policy": evaluation.disposition,
                },
            )
            if evaluation.rejected:
                await repository.append_audit(
                    tenant_id=candidate.tenant_id,
                    request_id=request_id,
                    action=AuditAction.REJECT,
                    aggregate_type="candidate",
                    aggregate_id=str(created.candidate_id),
                    actor=actor,
                    details={"user_id": candidate.user_id, "reason": reason},
                )
            await repository.put_idempotent(
                candidate.tenant_id,
                operation,
                request_id,
                created.model_dump(mode="json"),
            )
            return created

    async def review_candidate(
        self,
        candidate_id: UUID,
        decision: CandidateReview,
        *,
        actor: MemoryActor,
        request_id: str,
    ) -> MemoryItem | None:
        request_id = require_request_id(request_id)
        operation = f"candidate.review.{candidate_id}"
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            row = await repository.get_candidate_for_update(candidate_id)
            if row is None:
                raise MemoryNotFoundError("memory candidate not found")
            cached = await repository.get_idempotent(row.tenant_id, operation, request_id)
            if cached is not None:
                payload = cached.get("item")
                return MemoryItem.model_validate(payload) if payload else None
            if row.status != decision.expected_status.value:
                raise MemoryStateError(
                    f"candidate state is {row.status}, expected {decision.expected_status.value}"
                )
            candidate = candidate_model(row)
            candidate_create = candidate_create_from_model(candidate)
            if decision.decision is ReviewDecision.REJECT:
                await repository.finish_candidate(
                    row,
                    status=CandidateStatus.REJECTED,
                    actor=actor,
                    reason=decision.reason,
                )
                await repository.append_audit(
                    tenant_id=row.tenant_id,
                    request_id=request_id,
                    action=AuditAction.REJECT,
                    aggregate_type="candidate",
                    aggregate_id=str(candidate_id),
                    actor=actor,
                    details={"user_id": row.user_id, "reason": decision.reason},
                )
                await repository.put_idempotent(
                    row.tenant_id, operation, request_id, {"item": None}
                )
                return None

            self.review_policy.assert_approval_safe(candidate_create)
            existing = await repository.list_active_items(
                tenant_id=row.tenant_id,
                user_id=row.user_id,
                memory_type=row.memory_type,
            )
            resolution = self.conflict_resolver.resolve(candidate_create, existing)
            action = AuditAction.ITEM_CREATE
            if resolution.action is ConflictAction.DUPLICATE:
                item = next(
                    entry for entry in existing if entry.memory_id == resolution.existing_memory_id
                )
            elif resolution.action is ConflictAction.SUPERSEDE:
                current = next(
                    entry for entry in existing if entry.memory_id == resolution.existing_memory_id
                )
                item = await repository.supersede_item(
                    current,
                    MemoryUpdate(
                        tenant_id=row.tenant_id,
                        user_id=row.user_id,
                        content_text=row.content_text,
                        content_json=row.content_json,
                        confidence=row.confidence,
                        change_reason=decision.reason,
                        provenance={
                            **row.provenance,
                            "candidate_id": str(row.candidate_id),
                        },
                    ),
                    actor=actor,
                )
                action = AuditAction.SUPERSEDE
            else:
                item = await repository.create_item(
                    candidate_create,
                    canonical_key=canonical_key(candidate_create),
                    actor=actor,
                )
            await repository.finish_candidate(
                row,
                status=CandidateStatus.APPROVED,
                actor=actor,
                reason=decision.reason,
                accepted_memory_id=item.memory_id,
            )
            if resolution.action is not ConflictAction.DUPLICATE:
                await repository.append_audit(
                    tenant_id=row.tenant_id,
                    request_id=request_id,
                    action=action,
                    aggregate_type="memory",
                    aggregate_id=str(item.memory_id),
                    actor=actor,
                    details={
                        "user_id": row.user_id,
                        "candidate_id": str(candidate_id),
                        "version_id": str(item.current_version_id),
                    },
                )
            await repository.append_audit(
                tenant_id=row.tenant_id,
                request_id=request_id,
                action=AuditAction.APPROVE,
                aggregate_type="candidate",
                aggregate_id=str(candidate_id),
                actor=actor,
                details={
                    "user_id": row.user_id,
                    "memory_id": str(item.memory_id),
                    "resolution": resolution.action.value,
                },
            )
            response = {"item": item.model_dump(mode="json")}
            await repository.put_idempotent(row.tenant_id, operation, request_id, response)
            return item

    async def search(self, query: MemorySearchQuery) -> list[MemoryHit]:
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            retrieved = []
            modes = set(query.modes)
            if query.query_embedding is not None and (
                SearchMode.HYBRID in modes or SearchMode.EXACT_VECTOR in modes
            ):
                retrieved.extend(await repository.vector_search(query))
            if SearchMode.HYBRID in modes or SearchMode.KEYWORD in modes:
                retrieved.extend(await repository.keyword_search(query))
        combined: dict[UUID, dict[str, Any]] = {}
        for result in retrieved:
            entry = combined.setdefault(
                result.item.memory_id,
                {"item": result.item, "semantic": 0.0, "keyword": 0.0},
            )
            entry["semantic"] = max(entry["semantic"], result.semantic)
            entry["keyword"] = max(entry["keyword"], result.keyword)
        hits = [
            build_hit(
                entry["item"],
                semantic=entry["semantic"],
                keyword=entry["keyword"],
                now=query.now,
            )
            for entry in combined.values()
        ]
        return rerank_and_budget(
            hits, token_budget=query.token_budget, limit=query.limit
        )

    async def get(
        self,
        memory_id: UUID,
        *,
        tenant_id: str,
        user_id: str,
    ) -> MemoryItem:
        async with self.uow_factory() as uow:
            item = await repository_of(uow).get_item(
                memory_id, tenant_id=tenant_id, user_id=user_id
            )
        if item is None:
            raise MemoryNotFoundError("memory not found")
        return item

    async def supersede(
        self,
        memory_id: UUID,
        update: MemoryUpdate,
        *,
        actor: MemoryActor,
        request_id: str,
    ) -> MemoryItem:
        request_id = require_request_id(request_id)
        operation = f"memory.supersede.{memory_id}"
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            cached = await repository.get_idempotent(update.tenant_id, operation, request_id)
            if cached:
                return MemoryItem.model_validate(cached)
            current = await repository.get_item(
                memory_id,
                tenant_id=update.tenant_id,
                user_id=update.user_id,
                for_update=True,
            )
            if current is None:
                raise MemoryNotFoundError("memory not found")
            if current.status is not MemoryStatus.ACTIVE:
                raise MemoryStateError("only active memory can be superseded")
            updated = await repository.supersede_item(current, update, actor=actor)
            await repository.append_audit(
                tenant_id=update.tenant_id,
                request_id=request_id,
                action=AuditAction.SUPERSEDE,
                aggregate_type="memory",
                aggregate_id=str(memory_id),
                actor=actor,
                details={
                    "user_id": update.user_id,
                    "version_id": str(updated.current_version_id),
                },
            )
            await repository.put_idempotent(
                update.tenant_id,
                operation,
                request_id,
                updated.model_dump(mode="json"),
            )
            return updated

    async def delete(
        self,
        memory_id: UUID,
        *,
        tenant_id: str,
        user_id: str,
        reason: str,
        actor: MemoryActor,
        request_id: str,
    ) -> None:
        await self._change_visibility(
            memory_id,
            tenant_id=tenant_id,
            user_id=user_id,
            target=MemoryStatus.DELETED,
            reason=reason,
            actor=actor,
            request_id=request_id,
        )

    async def restore(
        self,
        memory_id: UUID,
        *,
        tenant_id: str,
        user_id: str,
        actor: MemoryActor,
        request_id: str,
    ) -> MemoryItem:
        return await self._change_visibility(
            memory_id,
            tenant_id=tenant_id,
            user_id=user_id,
            target=MemoryStatus.ACTIVE,
            reason="restore",
            actor=actor,
            request_id=request_id,
        )

    async def _change_visibility(
        self,
        memory_id: UUID,
        *,
        tenant_id: str,
        user_id: str,
        target: MemoryStatus,
        reason: str,
        actor: MemoryActor,
        request_id: str,
    ) -> MemoryItem:
        request_id = require_request_id(request_id)
        operation = f"memory.{target.value}.{memory_id}"
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            cached = await repository.get_idempotent(tenant_id, operation, request_id)
            if cached:
                return MemoryItem.model_validate(cached)
            current = await repository.get_item(
                memory_id, tenant_id=tenant_id, user_id=user_id, for_update=True
            )
            if current is None:
                raise MemoryNotFoundError("memory not found")
            if target is MemoryStatus.ACTIVE and current.status is not MemoryStatus.DELETED:
                raise MemoryStateError("only deleted memory can be restored")
            if target is MemoryStatus.DELETED and current.status is MemoryStatus.DELETED:
                raise MemoryStateError("memory is already deleted")
            updated = await repository.set_item_status(current, target)
            action = AuditAction.RESTORE if target is MemoryStatus.ACTIVE else AuditAction.DELETE
            await repository.append_audit(
                tenant_id=tenant_id,
                request_id=request_id,
                action=action,
                aggregate_type="memory",
                aggregate_id=str(memory_id),
                actor=actor,
                details={"user_id": user_id, "reason": reason},
            )
            await repository.put_idempotent(
                tenant_id, operation, request_id, updated.model_dump(mode="json")
            )
            return updated

    async def export_user(
        self,
        user_id: str,
        *,
        tenant_id: str,
        format: str,
        request_id: str,
    ) -> MemoryExport:
        request_id = require_request_id(request_id)
        if format != "jsonl":
            raise ValueError("only jsonl export is supported")
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            actor = MemoryActor(actor_type=ActorType.USER, actor_id=user_id)
            await repository.append_audit(
                tenant_id=tenant_id,
                request_id=request_id,
                action=AuditAction.EXPORT,
                aggregate_type="user",
                aggregate_id=user_id,
                actor=actor,
                details={"user_id": user_id, "format": format},
            )
            items = await repository.list_user_items(
                tenant_id=tenant_id, user_id=user_id, include_deleted=True
            )
            audits = await repository.list_audit_events(
                tenant_id=tenant_id, user_id=user_id
            )
            return MemoryExport(
                tenant_id=tenant_id,
                user_id=user_id,
                items=items,
                audit_events=audits,
            )

    async def bind_identity(
        self,
        binding: IdentityBindingCreate,
        *,
        actor: MemoryActor,
        request_id: str,
    ) -> IdentityBinding:
        request_id = require_request_id(request_id)
        operation = "identity.bind"
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            cached = await repository.get_idempotent(binding.tenant_id, operation, request_id)
            if cached:
                return IdentityBinding.model_validate(cached)
            result = await repository.bind_identity(binding)
            await repository.append_audit(
                tenant_id=binding.tenant_id,
                request_id=request_id,
                action=AuditAction.IDENTITY_BIND,
                aggregate_type="identity_binding",
                aggregate_id=str(result.binding_id),
                actor=actor,
                details={"user_id": binding.user_id, "platform": binding.platform},
            )
            await repository.put_idempotent(
                binding.tenant_id,
                operation,
                request_id,
                result.model_dump(mode="json"),
            )
            return result

    async def unbind_identity(
        self,
        binding_id: UUID,
        *,
        actor: MemoryActor,
        request_id: str,
    ) -> None:
        request_id = require_request_id(request_id)
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            row = await repository.get_binding_for_update(binding_id)
            if row is None:
                raise MemoryNotFoundError("identity binding not found")
            operation = f"identity.unbind.{binding_id}"
            if await repository.get_idempotent(row.tenant_id, operation, request_id):
                return
            await repository.unbind_identity(row)
            await repository.append_audit(
                tenant_id=row.tenant_id,
                request_id=request_id,
                action=AuditAction.IDENTITY_UNBIND,
                aggregate_type="identity_binding",
                aggregate_id=str(binding_id),
                actor=actor,
                details={"user_id": row.user_id, "platform": row.platform},
            )
            await repository.put_idempotent(
                row.tenant_id, operation, request_id, {"unbound": True}
            )

    async def summarize_session(
        self,
        summary: SessionSummaryUpsert,
        *,
        request_id: str,
    ) -> SessionSummaryUpsert:
        require_request_id(request_id)
        async with self.uow_factory() as uow:
            return await repository_of(uow).upsert_session_summary(summary)
