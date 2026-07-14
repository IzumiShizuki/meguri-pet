from __future__ import annotations

import hashlib
from time import perf_counter
from typing import Any
from uuid import UUID

from .conflict_resolver import ConflictResolver, canonical_key
from .contracts import (
    EmbeddingProvider,
    MemoryAuthorizationError,
    MemoryNotFoundError,
    MemoryStateError,
)
from .enums import (
    ActorType,
    AuditAction,
    CandidateStatus,
    ConflictAction,
    MemoryStatus,
    MemoryType,
    ReviewDecision,
    SearchMode,
)
from .models import (
    CandidateReview,
    HardDeleteResult,
    IdentityBinding,
    IdentityBindingCreate,
    MemoryActor,
    MemoryCandidate,
    MemoryCandidateCreate,
    MemoryExport,
    MemoryFeedback,
    MemoryFeedbackCreate,
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
from .metrics import MemoryMetrics, memory_metrics


def require_request_id(request_id: str) -> str:
    value = request_id.strip()
    if not value:
        raise ValueError("request_id must not be empty")
    if len(value) > 200:
        raise ValueError("request_id must be at most 200 characters")
    return value


def operation_key(base: str, *scope: str) -> str:
    if not scope:
        return base
    digest = hashlib.sha256("\0".join(scope).encode("utf-8")).hexdigest()[:32]
    return f"{base}.{digest}"


async def lock_idempotency(
    repository,
    tenant_id: str,
    operation: str,
    request_id: str,
) -> None:
    lock = getattr(repository, "lock_idempotency_key", None)
    if callable(lock):
        await lock(tenant_id, operation, request_id)


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
        metrics: MemoryMetrics | None = None,
        hard_delete_enabled: bool = False,
        embedding_provider: EmbeddingProvider | None = None,
    ) -> None:
        self.uow_factory = unit_of_work_factory
        self.review_policy = review_policy or CandidateReviewPolicy()
        self.conflict_resolver = conflict_resolver or ConflictResolver()
        self.metrics = metrics or memory_metrics
        self.hard_delete_enabled = hard_delete_enabled
        self.embedding_provider = embedding_provider

    async def create_candidate(
        self,
        candidate: MemoryCandidateCreate,
        *,
        request_id: str,
    ) -> MemoryCandidate:
        request_id = require_request_id(request_id)
        operation = operation_key("candidate.create", candidate.user_id)
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            await lock_idempotency(
                repository, candidate.tenant_id, operation, request_id
            )
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
            self.metrics.inc("memory_candidate_created_total")
            if evaluation.rejected:
                self.metrics.inc("memory_candidate_rejected_total")
            return created

    async def list_candidates(
        self,
        *,
        tenant_id: str,
        user_id: str,
        status: str | None = None,
    ) -> list[MemoryCandidate]:
        async with self.uow_factory() as uow:
            return await repository_of(uow).list_candidates(
                tenant_id=tenant_id, user_id=user_id, status=status
            )

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
            await lock_idempotency(
                repository, row.tenant_id, operation, request_id
            )
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
                self.metrics.inc("memory_candidate_rejected_total")
                return None

            self.review_policy.assert_approval_safe(candidate_create)
            existing = await repository.list_active_items(
                tenant_id=row.tenant_id,
                user_id=row.user_id,
                memory_type=row.memory_type,
            )
            semantic_scores: dict[object, float] = {}
            if self.embedding_provider is not None and existing:
                try:
                    vector = (
                        await self.embedding_provider.embed([row.content_text])
                    )[0]
                    semantic_matches = await repository.vector_search(
                        MemorySearchQuery(
                            tenant_id=row.tenant_id,
                            user_id=row.user_id,
                            query=row.content_text,
                            limit=min(50, max(5, len(existing))),
                            memory_types=[MemoryType(row.memory_type)],
                            modes=[SearchMode.EXACT_VECTOR],
                            query_embedding=vector,
                            embedding_model=self.embedding_provider.model,
                            embedding_revision=self.embedding_provider.revision,
                        )
                    )
                    semantic_scores = {
                        match.item.memory_id: match.semantic
                        for match in semantic_matches
                    }
                except Exception:
                    self.metrics.inc("memory_embedding_failure_total")
            resolution = self.conflict_resolver.resolve(
                candidate_create,
                existing,
                semantic_scores=semantic_scores,
            )
            if resolution.action in {ConflictAction.SUPERSEDE, ConflictAction.REJECT}:
                self.metrics.inc("memory_conflict_total")
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
            self.metrics.inc("memory_candidate_approved_total")
            if resolution.action is ConflictAction.CREATE:
                self.metrics.add_gauge("memory_active_total", 1)
            return item

    async def search(self, query: MemorySearchQuery) -> list[MemoryHit]:
        started = perf_counter()
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            retrieved = []
            modes = set(query.modes)
            if query.query_embedding is not None and (
                SearchMode.HYBRID in modes or SearchMode.EXACT_VECTOR in modes
            ):
                retrieved.extend(await repository.vector_search(query))
            if query.canonical_key is not None and (
                SearchMode.HYBRID in modes or SearchMode.STRUCTURED in modes
            ):
                retrieved.extend(await repository.structured_search(query))
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
        results = rerank_and_budget(
            hits, token_budget=query.token_budget, limit=query.limit
        )
        self.metrics.set_gauge(
            "memory_search_latency_ms", (perf_counter() - started) * 1000
        )
        self.metrics.set_gauge("memory_search_result_count", len(results))
        return results

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

    async def record_feedback(
        self,
        feedback: MemoryFeedbackCreate,
        *,
        request_id: str,
    ) -> MemoryFeedback:
        request_id = require_request_id(request_id)
        operation = operation_key(
            "memory.feedback",
            feedback.user_id,
            str(feedback.memory_id),
            str(feedback.version_id),
            feedback.feedback_kind.value,
        )
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            await lock_idempotency(
                repository, feedback.tenant_id, operation, request_id
            )
            cached = await repository.get_idempotent(
                feedback.tenant_id, operation, request_id
            )
            if cached:
                return MemoryFeedback.model_validate(cached)
            result = await repository.create_feedback(feedback)
            if result is None:
                raise MemoryNotFoundError("memory version not found")
            await repository.put_idempotent(
                feedback.tenant_id,
                operation,
                request_id,
                result.model_dump(mode="json"),
            )
            if feedback.feedback_kind.value == "false_recall":
                self.metrics.inc("memory_false_recall_feedback_total")
            return result

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
            await lock_idempotency(
                repository, update.tenant_id, operation, request_id
            )
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
            await lock_idempotency(
                repository, tenant_id, operation, request_id
            )
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
            self.metrics.add_gauge(
                "memory_active_total", 1 if target is MemoryStatus.ACTIVE else -1
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
        operation = operation_key("memory.export", user_id)
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            await lock_idempotency(
                repository, tenant_id, operation, request_id
            )
            cached = await repository.get_idempotent(
                tenant_id, operation, request_id
            )
            if cached:
                return MemoryExport.model_validate(cached)
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
            versions = await repository.list_user_versions(
                tenant_id=tenant_id, user_id=user_id
            )
            audits = await repository.list_audit_events(
                tenant_id=tenant_id, user_id=user_id
            )
            result = MemoryExport(
                tenant_id=tenant_id,
                user_id=user_id,
                items=items,
                versions=versions,
                audit_events=audits,
            )
            await repository.put_idempotent(
                tenant_id,
                operation,
                request_id,
                result.model_dump(mode="json"),
            )
            return result

    async def hard_delete(
        self,
        memory_id: UUID,
        *,
        tenant_id: str,
        user_id: str,
        reason: str,
        confirmation: str,
        actor: MemoryActor,
        request_id: str,
    ) -> HardDeleteResult:
        request_id = require_request_id(request_id)
        if not self.hard_delete_enabled:
            raise MemoryAuthorizationError("hard delete is disabled")
        if actor.actor_type is not ActorType.ADMIN:
            raise MemoryAuthorizationError("hard delete requires an administrator")
        if confirmation != f"HARD_DELETE:{memory_id}":
            raise MemoryAuthorizationError("hard delete confirmation did not match")
        if not reason.strip():
            raise ValueError("hard delete reason is required")
        operation = f"memory.hard_delete.{memory_id}"
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            await lock_idempotency(
                repository, tenant_id, operation, request_id
            )
            cached = await repository.get_idempotent(
                tenant_id, operation, request_id
            )
            if cached:
                return HardDeleteResult.model_validate(cached)
            current = await repository.get_item(
                memory_id,
                tenant_id=tenant_id,
                user_id=user_id,
                for_update=True,
            )
            if current is None:
                raise MemoryNotFoundError("memory not found")
            if current.status is not MemoryStatus.DELETED:
                raise MemoryStateError("memory must be soft deleted first")
            await repository.append_audit(
                tenant_id=tenant_id,
                request_id=request_id,
                action=AuditAction.HARD_DELETE,
                aggregate_type="memory",
                aggregate_id=str(memory_id),
                actor=actor,
                details={
                    "user_id": user_id,
                    "reason": reason,
                    "audit_retained": True,
                },
            )
            counts = await repository.hard_delete_item(current)
            result = HardDeleteResult(
                memory_id=memory_id,
                tenant_id=tenant_id,
                user_id=user_id,
                **counts,
            )
            await repository.put_idempotent(
                tenant_id,
                operation,
                request_id,
                result.model_dump(mode="json"),
            )
            return result

    async def bind_identity(
        self,
        binding: IdentityBindingCreate,
        *,
        actor: MemoryActor,
        request_id: str,
    ) -> IdentityBinding:
        request_id = require_request_id(request_id)
        operation = operation_key(
            "identity.bind",
            binding.user_id,
            binding.platform,
            binding.platform_user_id,
        )
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            await lock_idempotency(
                repository, binding.tenant_id, operation, request_id
            )
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

    async def list_identity_bindings(
        self,
        *,
        tenant_id: str,
        user_id: str,
    ) -> list[IdentityBinding]:
        async with self.uow_factory() as uow:
            return await repository_of(uow).list_identity_bindings(
                tenant_id=tenant_id, user_id=user_id
            )

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
            await lock_idempotency(
                repository, row.tenant_id, operation, request_id
            )
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
        request_id = require_request_id(request_id)
        operation = operation_key(
            "session.summary",
            summary.user_id,
            summary.client_id,
            summary.session_id,
        )
        async with self.uow_factory() as uow:
            repository = repository_of(uow)
            await lock_idempotency(
                repository, summary.tenant_id, operation, request_id
            )
            cached = await repository.get_idempotent(
                summary.tenant_id, operation, request_id
            )
            if cached:
                return SessionSummaryUpsert.model_validate(cached)
            result = await repository.upsert_session_summary(summary)
            await repository.put_idempotent(
                summary.tenant_id,
                operation,
                request_id,
                result.model_dump(mode="json"),
            )
            return result
