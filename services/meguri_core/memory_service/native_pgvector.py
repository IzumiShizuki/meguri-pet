from __future__ import annotations

from datetime import timezone
import os
from typing import overload
from uuid import UUID, uuid4

from sqlalchemy import text

from services.meguri_core.memory import (
    MemoryExtractionInput as LegacyExtractionInput,
    MemoryHit as LegacyMemoryHit,
    MemoryRecord as LegacyMemoryRecord,
    MemorySearchInput as LegacySearchInput,
    MemoryUpsertInput as LegacyUpsertInput,
    SessionSummary as LegacySessionSummary,
    SessionSummaryInput as LegacySessionSummaryInput,
)

from .contracts import EmbeddingProvider, MemoryNotFoundError
from .database import (
    MemoryDatabaseSettings,
    create_memory_engine,
    create_session_factory,
)
from .enums import ActorType, MemoryStatus, MemoryType, SourceKind
from .models import (
    CandidateReview,
    HardDeleteResult,
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
from .repository import MemoryUnitOfWorkFactory
from .service import MemoryService


LEGACY_TYPE_MAP: dict[str, MemoryType] = {
    "core_profile": MemoryType.USER_PROFILE,
    "preference": MemoryType.USER_PREFERENCE,
    "relationship": MemoryType.RELATIONSHIP_FACT,
    "shared_experience": MemoryType.RELATIONSHIP_FACT,
    "promise": MemoryType.COMMITMENT,
    "important_person": MemoryType.IMPORTANT_PERSON,
    "ongoing_project": MemoryType.LONG_TERM_PROJECT,
    "episodic": MemoryType.CORRECTED_FACT,
}


class NativePgvectorMemoryProvider:
    provider_name = "native_pgvector"

    def __init__(
        self,
        *,
        settings: MemoryDatabaseSettings | None = None,
        service: MemoryService | None = None,
        tenant_id: str | None = None,
        query_embedding_provider: EmbeddingProvider | None = None,
    ) -> None:
        if service is None:
            settings = settings or MemoryDatabaseSettings.from_env()
            self.settings = settings
            self.engine = create_memory_engine(settings)
            session_factory = create_session_factory(self.engine)
            self.uow_factory = MemoryUnitOfWorkFactory(session_factory)
            self.service = MemoryService(
                self.uow_factory,
                hard_delete_enabled=os.getenv(
                    "MEGURI_ALLOW_HARD_DELETE", "false"
                ).lower()
                == "true",
            )
            self.tenant_id = settings.tenant_id
        else:
            if not tenant_id:
                raise ValueError("tenant_id is required when injecting a memory service")
            self.engine = None
            self.settings = None
            self.uow_factory = service.uow_factory
            self.service = service
            self.tenant_id = tenant_id
        self.query_embedding_provider = query_embedding_provider

    @classmethod
    def from_env(cls) -> "NativePgvectorMemoryProvider":
        return cls(settings=MemoryDatabaseSettings.from_env())

    async def close(self) -> None:
        if self.engine is not None:
            await self.engine.dispose()

    async def health(self) -> dict[str, str]:
        if self.engine is None:
            return {"status": "ok", "provider": self.provider_name, "database": "injected"}
        async with self.engine.connect() as connection:
            revision = await connection.scalar(text("SELECT version_num FROM alembic_version"))
            await connection.execute(text("SELECT 1"))
        expected_revision = (
            self.settings.expected_database_revision if self.settings else None
        )
        status = (
            "revision_mismatch"
            if expected_revision and str(revision) != expected_revision
            else "ok"
        )
        return {
            "status": status,
            "provider": self.provider_name,
            "database_revision": str(revision),
            "expected_database_revision": expected_revision or "not-configured",
        }

    async def create_candidate(
        self,
        candidate: MemoryCandidateCreate,
        *,
        request_id: str,
    ) -> MemoryCandidate:
        return await self.service.create_candidate(candidate, request_id=request_id)

    async def list_candidates(
        self,
        *,
        tenant_id: str,
        user_id: str,
        status: str | None = None,
    ) -> list[MemoryCandidate]:
        return await self.service.list_candidates(
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
        return await self.service.review_candidate(
            candidate_id, decision, actor=actor, request_id=request_id
        )

    @overload
    async def search(self, query: MemorySearchQuery) -> list[MemoryHit]: ...

    @overload
    async def search(self, query: LegacySearchInput) -> list[LegacyMemoryHit]: ...

    async def search(self, query):
        if isinstance(query, MemorySearchQuery):
            return await self.service.search(query)
        if not isinstance(query, LegacySearchInput):
            raise TypeError("search requires MemorySearchQuery or legacy MemorySearchInput")
        embedding = None
        model = revision = None
        if self.query_embedding_provider is not None:
            embedding = (await self.query_embedding_provider.embed([query.query]))[0]
            model = self.query_embedding_provider.model
            revision = self.query_embedding_provider.revision
        authoritative = await self.service.search(
            MemorySearchQuery(
                tenant_id=self.tenant_id,
                user_id=query.user_id,
                query=query.query,
                limit=query.limit,
                memory_types=[LEGACY_TYPE_MAP[kind] for kind in query.memory_types if kind in LEGACY_TYPE_MAP],
                query_embedding=embedding,
                embedding_model=model,
                embedding_revision=revision,
            )
        )
        return [self._legacy_hit(hit, query.user_id) for hit in authoritative]

    async def get(
        self,
        memory_id: UUID,
        *,
        tenant_id: str,
        user_id: str,
    ) -> MemoryItem:
        return await self.service.get(memory_id, tenant_id=tenant_id, user_id=user_id)

    async def supersede(self, memory_id, update, **kwargs):
        if isinstance(update, MemoryUpdate):
            return await self.service.supersede(memory_id, update, **kwargs)
        if not isinstance(update, LegacyUpsertInput):
            raise TypeError("supersede requires MemoryUpdate or legacy MemoryUpsertInput")
        parsed_id = UUID(str(memory_id))
        actor = MemoryActor(actor_type=ActorType.SYSTEM, actor_id="legacy-runtime")
        result = await self.service.supersede(
            parsed_id,
            self._authoritative_update(update),
            actor=actor,
            request_id=f"legacy-supersede-{uuid4()}",
        )
        return self._legacy_record(result)

    async def delete(self, memory_id, **kwargs) -> None:
        parsed_id = UUID(str(memory_id))
        if kwargs:
            await self.service.delete(parsed_id, **kwargs)
            return
        user_id = await self._owner_for_legacy_call(parsed_id)
        await self.service.delete(
            parsed_id,
            tenant_id=self.tenant_id,
            user_id=user_id,
            reason="legacy_api_delete",
            actor=MemoryActor(actor_type=ActorType.SYSTEM, actor_id="legacy-runtime"),
            request_id=f"legacy-delete-{uuid4()}",
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
        return await self.service.restore(
            memory_id,
            tenant_id=tenant_id,
            user_id=user_id,
            actor=actor,
            request_id=request_id,
        )

    async def export_user(
        self,
        user_id: str,
        *,
        tenant_id: str,
        format: str,
        request_id: str,
    ) -> MemoryExport:
        return await self.service.export_user(
            user_id, tenant_id=tenant_id, format=format, request_id=request_id
        )

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
        return await self.service.hard_delete(
            memory_id,
            tenant_id=tenant_id,
            user_id=user_id,
            reason=reason,
            confirmation=confirmation,
            actor=actor,
            request_id=request_id,
        )

    async def bind_identity(
        self,
        binding: IdentityBindingCreate,
        *,
        actor: MemoryActor,
        request_id: str,
    ) -> IdentityBinding:
        return await self.service.bind_identity(
            binding, actor=actor, request_id=request_id
        )

    async def list_identity_bindings(
        self,
        *,
        tenant_id: str,
        user_id: str,
    ) -> list[IdentityBinding]:
        return await self.service.list_identity_bindings(
            tenant_id=tenant_id, user_id=user_id
        )

    async def unbind_identity(
        self,
        binding_id: UUID,
        *,
        actor: MemoryActor,
        request_id: str,
    ) -> None:
        await self.service.unbind_identity(
            binding_id, actor=actor, request_id=request_id
        )

    async def resolve_identity(
        self,
        *,
        tenant_id: str,
        platform: str,
        platform_user_id: str,
    ) -> str | None:
        async with self.uow_factory() as uow:
            repository = uow.repository
            if repository is None:
                raise RuntimeError("memory unit of work is not active")
            return await repository.resolve_identity(
                tenant_id=tenant_id,
                platform=platform,
                platform_user_id=platform_user_id,
            )

    async def summarize_session(self, summary, **kwargs):
        if isinstance(summary, SessionSummaryUpsert):
            return await self.service.summarize_session(summary, **kwargs)
        if not isinstance(summary, LegacySessionSummaryInput):
            raise TypeError("summarize_session received an unknown input model")
        content = " ".join(
            message.content.strip() for message in summary.messages if message.content.strip()
        )
        authoritative = SessionSummaryUpsert(
            tenant_id=self.tenant_id,
            user_id=summary.user_id,
            client_id=summary.client_id,
            session_id=summary.session_id,
            summary_text=content[:10000],
            summary_json={"message_count": len(summary.messages)},
            source_range={"message_count": len(summary.messages)},
        )
        await self.service.summarize_session(
            authoritative, request_id=f"legacy-summary-{uuid4()}"
        )
        return LegacySessionSummary(
            user_id=summary.user_id,
            client_id=summary.client_id,
            session_id=summary.session_id,
            summary=authoritative.summary_text[:1000],
            message_count=len(summary.messages),
        )

    async def extract_candidates(
        self, _input: LegacyExtractionInput
    ) -> list:
        # Candidate extraction remains in the validated LLM response/policy layer.
        return []

    async def upsert(self, legacy: LegacyUpsertInput) -> LegacyMemoryRecord:
        if legacy.memory_type in {"recent_emotion", "session_summary"}:
            raise ValueError("short-term state cannot be promoted through legacy upsert")
        mapped_type = LEGACY_TYPE_MAP.get(legacy.memory_type)
        if mapped_type is None:
            raise ValueError(f"unsupported legacy memory type: {legacy.memory_type}")
        request_id = f"legacy-upsert-{uuid4()}"
        candidate = await self.create_candidate(
            MemoryCandidateCreate(
                tenant_id=self.tenant_id,
                user_id=legacy.user_id,
                memory_type=mapped_type,
                content_text=legacy.canonical_text,
                content_json={
                    "importance": legacy.importance / 5,
                    "legacy_memory_type": legacy.memory_type,
                },
                confidence=legacy.confidence,
                sensitivity=legacy.sensitivity,
                source_client_id=legacy.source_client,
                source_session_id=legacy.source_session,
                source_turn_id=request_id,
                source_kind=SourceKind.LLM_CANDIDATE,
                provenance={"compatibility_facade": True},
            ),
            request_id=request_id,
        )
        if candidate.status.value == "rejected":
            raise ValueError(f"legacy memory candidate rejected: {candidate.review_reason}")
        item = await self.review_candidate(
            candidate.candidate_id,
            CandidateReview(decision="approve", reason="legacy compatibility review"),
            actor=MemoryActor(actor_type=ActorType.POLICY, actor_id="legacy-runtime"),
            request_id=f"{request_id}-review",
        )
        if item is None:
            raise RuntimeError("approved legacy candidate did not produce a memory item")
        return self._legacy_record(item)

    async def list_records(
        self, user_id: str, include_deleted: bool = False
    ) -> list[LegacyMemoryRecord]:
        async with self.uow_factory() as uow:
            repository = uow.repository
            if repository is None:
                raise RuntimeError("memory unit of work is not active")
            items = await repository.list_user_items(
                tenant_id=self.tenant_id,
                user_id=user_id,
                include_deleted=include_deleted,
            )
        return [self._legacy_record(item) for item in items]

    async def _owner_for_legacy_call(self, memory_id: UUID) -> str:
        async with self.uow_factory() as uow:
            repository = uow.repository
            if repository is None:
                raise RuntimeError("memory unit of work is not active")
            user_id = await repository.get_item_owner(memory_id, tenant_id=self.tenant_id)
        if user_id is None:
            raise MemoryNotFoundError("memory not found")
        return user_id

    def _authoritative_update(self, legacy: LegacyUpsertInput) -> MemoryUpdate:
        return MemoryUpdate(
            tenant_id=self.tenant_id,
            user_id=legacy.user_id,
            content_text=legacy.canonical_text,
            content_json={
                "importance": legacy.importance / 5,
                "legacy_memory_type": legacy.memory_type,
            },
            confidence=legacy.confidence,
            importance=legacy.importance / 5,
            expires_at=legacy.expires_at,
            change_reason="legacy compatibility supersede",
            provenance={
                "compatibility_facade": True,
                "source_client_id": legacy.source_client,
                "source_session_id": legacy.source_session,
            },
        )

    @staticmethod
    def _legacy_record(item: MemoryItem) -> LegacyMemoryRecord:
        version = item.current_version
        if version is None:
            raise RuntimeError("memory item is missing current version")
        provenance = version.provenance
        reverse_types = {
            MemoryType.USER_PROFILE: "core_profile",
            MemoryType.USER_PREFERENCE: "preference",
            MemoryType.IMPORTANT_PERSON: "important_person",
            MemoryType.LONG_TERM_PROJECT: "ongoing_project",
            MemoryType.COMMITMENT: "promise",
            MemoryType.RELATIONSHIP_FACT: "relationship",
            MemoryType.RECURRING_HABIT: "preference",
            MemoryType.CORRECTED_FACT: "episodic",
        }
        status = (
            "active"
            if item.status is MemoryStatus.ACTIVE
            else "deleted"
            if item.status is MemoryStatus.DELETED
            else "superseded"
        )
        return LegacyMemoryRecord(
            memory_id=str(item.memory_id),
            user_id=item.user_id,
            memory_type=reverse_types[item.memory_type],
            canonical_text=version.content_text,
            source_client=str(provenance.get("source_client_id") or "native_pgvector"),
            source_session=str(provenance.get("source_session_id") or "unknown"),
            confidence=item.confidence,
            sensitivity="normal",
            importance=max(1, min(5, round(item.importance * 5))),
            status=status,
            version=version.version_no,
            expires_at=item.expires_at,
            created_at=item.created_at.astimezone(timezone.utc),
            updated_at=item.updated_at.astimezone(timezone.utc),
        )

    @staticmethod
    def _legacy_hit(hit: MemoryHit, user_id: str) -> LegacyMemoryHit:
        item = MemoryItem(
            memory_id=hit.memory_id,
            tenant_id="compatibility",
            user_id=user_id,
            memory_type=hit.memory_type,
            scope="global_user",
            status="active",
            current_version_id=hit.version_id,
            importance=hit.score_components.importance,
            confidence=hit.score_components.confidence,
            created_at=hit.created_at,
            updated_at=hit.updated_at,
            current_version={
                "version_id": hit.version_id,
                "memory_id": hit.memory_id,
                "version_no": 1,
                "content_text": hit.content_text,
                "change_reason": "retrieved",
                "provenance": hit.provenance,
                "created_by_type": "system",
                "created_at": hit.created_at,
            },
        )
        return LegacyMemoryHit(score=hit.score, record=NativePgvectorMemoryProvider._legacy_record(item))
