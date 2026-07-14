from __future__ import annotations

from datetime import datetime
from typing import Any
from uuid import UUID, uuid4

from pgvector.sqlalchemy import VECTOR
from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    Float,
    ForeignKey,
    ForeignKeyConstraint,
    Index,
    Integer,
    MetaData,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID as PG_UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from .enums import (
    ActorType,
    AuditAction,
    CandidateStatus,
    EmbeddingStatus,
    FeedbackKind,
    IdentityBindingStatus,
    MemoryScope,
    MemoryStatus,
    MemoryType,
    OutboxStatus,
    Sensitivity,
    SourceKind,
)


NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)


def _enum_values(enum_type: type) -> str:
    return ", ".join(f"'{member.value}'" for member in enum_type)


def _enum_check(column: str, enum_type: type, name: str) -> CheckConstraint:
    return CheckConstraint(f"{column} IN ({_enum_values(enum_type)})", name=name)


class IdentityBindingRow(Base):
    __tablename__ = "identity_bindings"
    __table_args__ = (
        _enum_check("status", IdentityBindingStatus, "valid_status"),
        Index(
            "uq_identity_bindings_active_platform_identity",
            "tenant_id",
            "platform",
            "platform_user_id",
            unique=True,
            postgresql_where=text("status = 'active'"),
        ),
    )

    binding_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), primary_key=True, default=uuid4
    )
    tenant_id: Mapped[str] = mapped_column(String(100), nullable=False)
    user_id: Mapped[str] = mapped_column(String(200), nullable=False)
    platform: Mapped[str] = mapped_column(String(100), nullable=False)
    platform_user_id: Mapped[str] = mapped_column(String(300), nullable=False)
    status: Mapped[str] = mapped_column(String(40), nullable=False)
    verification_method: Mapped[str | None] = mapped_column(String(100))
    verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )


class MemoryCandidateRow(Base):
    __tablename__ = "memory_candidates"
    __table_args__ = (
        _enum_check("memory_type", MemoryType, "valid_memory_type"),
        _enum_check("sensitivity", Sensitivity, "valid_sensitivity"),
        _enum_check("status", CandidateStatus, "valid_status"),
        _enum_check("source_kind", SourceKind, "valid_source_kind"),
        CheckConstraint("confidence >= 0 AND confidence <= 1", name="confidence_range"),
    )

    candidate_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), primary_key=True, default=uuid4
    )
    tenant_id: Mapped[str] = mapped_column(String(100), nullable=False)
    user_id: Mapped[str] = mapped_column(String(200), nullable=False)
    memory_type: Mapped[str] = mapped_column(String(50), nullable=False)
    content_text: Mapped[str] = mapped_column(Text, nullable=False)
    content_json: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    confidence: Mapped[float] = mapped_column(Float, nullable=False)
    sensitivity: Mapped[str] = mapped_column(String(30), nullable=False)
    source_client_id: Mapped[str] = mapped_column(String(100), nullable=False)
    source_session_id: Mapped[str] = mapped_column(String(200), nullable=False)
    source_turn_id: Mapped[str] = mapped_column(String(200), nullable=False)
    source_message_ids: Mapped[list[str]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    source_kind: Mapped[str] = mapped_column(String(50), nullable=False)
    extraction_model: Mapped[str | None] = mapped_column(String(300))
    extraction_prompt_hash: Mapped[str | None] = mapped_column(String(64))
    provenance: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    status: Mapped[str] = mapped_column(String(40), nullable=False)
    review_reason: Mapped[str | None] = mapped_column(Text)
    reviewed_by: Mapped[str | None] = mapped_column(String(200))
    reviewed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    accepted_memory_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )


class MemoryItemRow(Base):
    __tablename__ = "memory_items"
    __table_args__ = (
        _enum_check("memory_type", MemoryType, "valid_memory_type"),
        _enum_check("scope", MemoryScope, "valid_scope"),
        _enum_check("status", MemoryStatus, "valid_status"),
        CheckConstraint("importance >= 0 AND importance <= 1", name="importance_range"),
        CheckConstraint("confidence >= 0 AND confidence <= 1", name="confidence_range"),
        CheckConstraint(
            "status <> 'active' OR current_version_id IS NOT NULL",
            name="active_has_current_version",
        ),
        ForeignKeyConstraint(
            ["memory_id", "current_version_id"],
            ["memory_versions.memory_id", "memory_versions.version_id"],
            name="fk_memory_items_current_version_same_item",
            use_alter=True,
            deferrable=True,
            initially="DEFERRED",
        ),
    )

    memory_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), primary_key=True, default=uuid4
    )
    tenant_id: Mapped[str] = mapped_column(String(100), nullable=False)
    user_id: Mapped[str] = mapped_column(String(200), nullable=False)
    memory_type: Mapped[str] = mapped_column(String(50), nullable=False)
    scope: Mapped[str] = mapped_column(String(50), nullable=False)
    status: Mapped[str] = mapped_column(String(40), nullable=False)
    canonical_key: Mapped[str | None] = mapped_column(String(500))
    current_version_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    importance: Mapped[float] = mapped_column(
        Float, nullable=False, server_default=text("0.5")
    )
    confidence: Mapped[float] = mapped_column(
        Float, nullable=False, server_default=text("0.5")
    )
    effective_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class MemoryVersionRow(Base):
    __tablename__ = "memory_versions"
    __table_args__ = (
        UniqueConstraint("memory_id", "version_no", name="uq_memory_versions_item_number"),
        UniqueConstraint("memory_id", "version_id", name="uq_memory_versions_item_version"),
        _enum_check("created_by_type", ActorType, "valid_created_by_type"),
    )

    version_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), primary_key=True, default=uuid4
    )
    memory_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("memory_items.memory_id", ondelete="CASCADE"),
        nullable=False,
    )
    version_no: Mapped[int] = mapped_column(Integer, nullable=False)
    content_text: Mapped[str] = mapped_column(Text, nullable=False)
    content_json: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    language: Mapped[str | None] = mapped_column(String(30))
    relationship_stage: Mapped[str | None] = mapped_column(String(100))
    supersedes_version_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("memory_versions.version_id")
    )
    change_reason: Mapped[str] = mapped_column(Text, nullable=False)
    provenance: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    created_by_type: Mapped[str] = mapped_column(String(30), nullable=False)
    created_by_id: Mapped[str | None] = mapped_column(String(200))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )


class MemoryEmbeddingRow(Base):
    __tablename__ = "memory_embeddings"
    __table_args__ = (
        UniqueConstraint(
            "version_id",
            "embedding_model",
            "embedding_revision",
            name="uq_memory_embeddings_version_model_revision",
        ),
        _enum_check("status", EmbeddingStatus, "valid_status"),
        CheckConstraint("embedding_dimension = 1024", name="dimension_1024"),
    )

    embedding_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), primary_key=True, default=uuid4
    )
    version_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("memory_versions.version_id", ondelete="CASCADE"),
        nullable=False,
    )
    embedding_model: Mapped[str] = mapped_column(String(300), nullable=False)
    embedding_revision: Mapped[str] = mapped_column(String(300), nullable=False)
    embedding_dimension: Mapped[int] = mapped_column(Integer, nullable=False)
    embedding: Mapped[list[float]] = mapped_column(VECTOR(1024), nullable=False)
    content_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(30), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )


class MemoryFeedbackRow(Base):
    __tablename__ = "memory_feedback"
    __table_args__ = (
        _enum_check("feedback_kind", FeedbackKind, "valid_feedback_kind"),
        CheckConstraint("hit_rank IS NULL OR hit_rank >= 1", name="positive_hit_rank"),
    )

    feedback_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), primary_key=True, default=uuid4
    )
    tenant_id: Mapped[str] = mapped_column(String(100), nullable=False)
    user_id: Mapped[str] = mapped_column(String(200), nullable=False)
    memory_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("memory_items.memory_id", ondelete="CASCADE"),
        nullable=False,
    )
    version_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("memory_versions.version_id"), nullable=False
    )
    feedback_kind: Mapped[str] = mapped_column(String(40), nullable=False)
    query_text: Mapped[str | None] = mapped_column(Text)
    hit_rank: Mapped[int | None] = mapped_column(Integer)
    details: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )


class SessionSummaryRow(Base):
    __tablename__ = "session_summaries"
    __table_args__ = (
        UniqueConstraint(
            "tenant_id",
            "user_id",
            "client_id",
            "session_id",
            name="uq_session_summaries_identity",
        ),
    )

    summary_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), primary_key=True, default=uuid4
    )
    tenant_id: Mapped[str] = mapped_column(String(100), nullable=False)
    user_id: Mapped[str] = mapped_column(String(200), nullable=False)
    client_id: Mapped[str] = mapped_column(String(100), nullable=False)
    session_id: Mapped[str] = mapped_column(String(200), nullable=False)
    summary_text: Mapped[str] = mapped_column(Text, nullable=False)
    summary_json: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    source_range: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    version: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("1"))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )


class MemoryAuditLogRow(Base):
    __tablename__ = "memory_audit_log"
    __table_args__ = (
        _enum_check("action", AuditAction, "valid_action"),
        _enum_check("actor_type", ActorType, "valid_actor_type"),
    )

    audit_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    tenant_id: Mapped[str] = mapped_column(String(100), nullable=False)
    request_id: Mapped[str] = mapped_column(String(200), nullable=False)
    action: Mapped[str] = mapped_column(String(50), nullable=False)
    aggregate_type: Mapped[str] = mapped_column(String(50), nullable=False)
    aggregate_id: Mapped[str] = mapped_column(String(200), nullable=False)
    actor_type: Mapped[str] = mapped_column(String(30), nullable=False)
    actor_id: Mapped[str | None] = mapped_column(String(200))
    details: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )


class MemoryOutboxRow(Base):
    __tablename__ = "memory_outbox"
    __table_args__ = (_enum_check("status", OutboxStatus, "valid_status"),)

    outbox_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), primary_key=True, default=uuid4
    )
    event_type: Mapped[str] = mapped_column(String(100), nullable=False)
    aggregate_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    payload: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    status: Mapped[str] = mapped_column(String(30), nullable=False)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    available_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    locked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    locked_by: Mapped[str | None] = mapped_column(String(200))
    last_error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class MemoryIdempotencyRow(Base):
    __tablename__ = "memory_idempotency"
    __table_args__ = (
        UniqueConstraint(
            "tenant_id", "operation", "request_id", name="uq_memory_idempotency_request"
        ),
    )

    idempotency_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), primary_key=True, default=uuid4
    )
    tenant_id: Mapped[str] = mapped_column(String(100), nullable=False)
    operation: Mapped[str] = mapped_column(String(100), nullable=False)
    request_id: Mapped[str] = mapped_column(String(200), nullable=False)
    response_json: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )


Index(
    "ix_memory_candidates_status_created_at",
    MemoryCandidateRow.status,
    MemoryCandidateRow.created_at,
)
Index(
    "ix_memory_items_tenant_user_status_type",
    MemoryItemRow.tenant_id,
    MemoryItemRow.user_id,
    MemoryItemRow.status,
    MemoryItemRow.memory_type,
)
Index(
    "ix_memory_items_tenant_user_canonical_key",
    MemoryItemRow.tenant_id,
    MemoryItemRow.user_id,
    MemoryItemRow.canonical_key,
)
Index("ix_memory_versions_memory_version_no", MemoryVersionRow.memory_id, MemoryVersionRow.version_no)
Index(
    "ix_memory_embeddings_version_model_revision",
    MemoryEmbeddingRow.version_id,
    MemoryEmbeddingRow.embedding_model,
    MemoryEmbeddingRow.embedding_revision,
)
Index(
    "ix_session_summaries_identity",
    SessionSummaryRow.tenant_id,
    SessionSummaryRow.user_id,
    SessionSummaryRow.client_id,
    SessionSummaryRow.session_id,
)
Index(
    "ix_memory_outbox_status_available_at",
    MemoryOutboxRow.status,
    MemoryOutboxRow.available_at,
)
Index("ix_memory_audit_request_id", MemoryAuditLogRow.request_id)
