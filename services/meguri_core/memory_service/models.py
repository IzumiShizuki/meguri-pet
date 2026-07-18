from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .enums import (
    ActorType,
    CandidateStatus,
    ConflictAction,
    FeedbackKind,
    IdentityBindingStatus,
    MemoryScope,
    MemoryStatus,
    MemoryType,
    ReviewDecision,
    SearchMode,
    Sensitivity,
    SourceKind,
)


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _timezone_required(value: datetime | None) -> datetime | None:
    if value is not None and value.tzinfo is None:
        raise ValueError("datetime values must include a timezone offset")
    return value


class StrictModel(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        str_strip_whitespace=True,
        validate_assignment=True,
    )


class MemoryActor(StrictModel):
    actor_type: ActorType
    actor_id: str = Field(min_length=1, max_length=200)


class MemoryCandidateCreate(StrictModel):
    tenant_id: str = Field(min_length=1, max_length=100)
    user_id: str = Field(min_length=1, max_length=200)
    memory_type: MemoryType
    content_text: str = Field(min_length=1, max_length=4000)
    content_json: dict[str, Any] = Field(default_factory=dict)
    confidence: float = Field(ge=0, le=1)
    sensitivity: Sensitivity = Sensitivity.NORMAL
    source_client_id: str = Field(min_length=1, max_length=100)
    source_session_id: str = Field(min_length=1, max_length=200)
    source_turn_id: str = Field(min_length=1, max_length=200)
    source_message_ids: list[str] = Field(default_factory=list, max_length=50)
    source_kind: SourceKind = SourceKind.LLM_CANDIDATE
    extraction_model: str | None = Field(default=None, max_length=300)
    extraction_prompt_hash: str | None = Field(default=None, max_length=64)
    provenance: dict[str, Any] = Field(default_factory=dict)


class MemoryCandidate(MemoryCandidateCreate):
    candidate_id: UUID
    status: CandidateStatus
    review_reason: str | None = Field(default=None, max_length=1000)
    reviewed_by: str | None = Field(default=None, max_length=200)
    reviewed_at: datetime | None = None
    accepted_memory_id: UUID | None = None
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)

    _validate_datetimes = field_validator(
        "reviewed_at", "created_at", "updated_at"
    )(_timezone_required)


class CandidateReview(StrictModel):
    decision: ReviewDecision
    reason: str = Field(min_length=1, max_length=1000)
    expected_status: CandidateStatus = CandidateStatus.PENDING_REVIEW
    auto_approve: bool = False


class MemoryUpdate(StrictModel):
    tenant_id: str = Field(min_length=1, max_length=100)
    user_id: str = Field(min_length=1, max_length=200)
    content_text: str = Field(min_length=1, max_length=4000)
    content_json: dict[str, Any] = Field(default_factory=dict)
    change_reason: str = Field(min_length=1, max_length=1000)
    confidence: float | None = Field(default=None, ge=0, le=1)
    importance: float | None = Field(default=None, ge=0, le=1)
    effective_at: datetime | None = None
    expires_at: datetime | None = None
    relationship_stage: str | None = Field(default=None, max_length=100)
    provenance: dict[str, Any] = Field(default_factory=dict)

    _validate_datetimes = field_validator("effective_at", "expires_at")(
        _timezone_required
    )


class MemoryVersion(StrictModel):
    version_id: UUID
    memory_id: UUID
    version_no: int = Field(ge=1)
    content_text: str
    content_json: dict[str, Any] = Field(default_factory=dict)
    language: str | None = None
    relationship_stage: str | None = None
    supersedes_version_id: UUID | None = None
    change_reason: str
    provenance: dict[str, Any]
    created_by_type: ActorType
    created_by_id: str | None = None
    created_at: datetime

    _validate_created_at = field_validator("created_at")(_timezone_required)


class MemoryItem(StrictModel):
    memory_id: UUID
    tenant_id: str
    user_id: str
    memory_type: MemoryType
    scope: MemoryScope
    status: MemoryStatus
    canonical_key: str | None = None
    current_version_id: UUID
    importance: float = Field(ge=0, le=1)
    confidence: float = Field(ge=0, le=1)
    effective_at: datetime | None = None
    expires_at: datetime | None = None
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None = None
    current_version: MemoryVersion | None = None

    _validate_datetimes = field_validator(
        "effective_at", "expires_at", "created_at", "updated_at", "deleted_at"
    )(_timezone_required)


class MemoryScoreComponents(StrictModel):
    semantic: float = Field(default=0, ge=0, le=1)
    keyword: float = Field(default=0, ge=0, le=1)
    importance: float = Field(default=0, ge=0, le=1)
    confidence: float = Field(default=0, ge=0, le=1)
    recency: float = Field(default=0, ge=0, le=1)


class MemoryHit(StrictModel):
    memory_id: UUID
    version_id: UUID
    memory_type: MemoryType
    content_text: str
    score: float = Field(ge=0, le=1)
    score_components: MemoryScoreComponents
    provenance: dict[str, Any]
    created_at: datetime
    updated_at: datetime

    _validate_datetimes = field_validator("created_at", "updated_at")(
        _timezone_required
    )


class MemoryFeedbackCreate(StrictModel):
    tenant_id: str = Field(min_length=1, max_length=100)
    user_id: str = Field(min_length=1, max_length=200)
    memory_id: UUID
    version_id: UUID
    feedback_kind: FeedbackKind
    query_text: str | None = Field(default=None, max_length=4000)
    hit_rank: int | None = Field(default=None, ge=1)
    details: dict[str, Any] = Field(default_factory=dict)


class MemoryFeedback(MemoryFeedbackCreate):
    feedback_id: UUID
    created_at: datetime

    _validate_created_at = field_validator("created_at")(_timezone_required)


class MemorySearchQuery(StrictModel):
    tenant_id: str = Field(min_length=1, max_length=100)
    user_id: str = Field(min_length=1, max_length=200)
    query: str = Field(min_length=1, max_length=4000)
    canonical_key: str | None = Field(default=None, min_length=1, max_length=500)
    limit: int = Field(default=5, ge=1, le=50)
    memory_types: list[MemoryType] = Field(default_factory=list)
    scopes: list[MemoryScope] = Field(
        default_factory=lambda: [MemoryScope.GLOBAL_USER]
    )
    modes: list[SearchMode] = Field(
        default_factory=lambda: [SearchMode.HYBRID]
    )
    token_budget: int = Field(default=1200, ge=64, le=8192)
    query_embedding: list[float] | None = Field(
        default=None, min_length=1024, max_length=1024
    )
    embedding_model: str | None = Field(default=None, max_length=300)
    embedding_revision: str | None = Field(default=None, max_length=300)
    now: datetime = Field(default_factory=utc_now)

    _validate_now = field_validator("now")(_timezone_required)


class MemoryExport(StrictModel):
    tenant_id: str
    user_id: str
    format: Literal["jsonl"] = "jsonl"
    generated_at: datetime = Field(default_factory=utc_now)
    items: list[MemoryItem]
    versions: list[MemoryVersion]
    audit_events: list[dict[str, Any]] = Field(default_factory=list)

    _validate_generated_at = field_validator("generated_at")(_timezone_required)


class HardDeleteResult(StrictModel):
    memory_id: UUID
    tenant_id: str
    user_id: str
    deleted_versions: int = Field(ge=0)
    deleted_candidates: int = Field(ge=0)
    audit_retained: bool = True


class IdentityBindingCreate(StrictModel):
    tenant_id: str = Field(min_length=1, max_length=100)
    user_id: str = Field(min_length=1, max_length=200)
    platform: str = Field(min_length=1, max_length=100)
    platform_user_id: str = Field(min_length=1, max_length=300)
    verification_method: str = Field(min_length=1, max_length=100)


class IdentityBinding(IdentityBindingCreate):
    binding_id: UUID
    status: IdentityBindingStatus
    verified_at: datetime | None = None
    created_at: datetime
    updated_at: datetime

    _validate_datetimes = field_validator(
        "verified_at", "created_at", "updated_at"
    )(_timezone_required)


class SessionSummaryUpsert(StrictModel):
    tenant_id: str = Field(min_length=1, max_length=100)
    user_id: str = Field(min_length=1, max_length=200)
    client_id: str = Field(min_length=1, max_length=100)
    session_id: str = Field(min_length=1, max_length=200)
    summary_text: str = Field(min_length=1, max_length=10000)
    summary_json: dict[str, Any] = Field(default_factory=dict)
    source_range: dict[str, Any]


class ConflictResolution(StrictModel):
    action: ConflictAction
    reason: str
    existing_memory_id: UUID | None = None
    existing_version_id: UUID | None = None
    similarity: float | None = Field(default=None, ge=0, le=1)
