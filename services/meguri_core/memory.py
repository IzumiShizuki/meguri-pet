from __future__ import annotations

import re
from collections import defaultdict, deque
from datetime import datetime, timezone
from typing import Literal, Protocol, runtime_checkable

from pydantic import BaseModel, Field, field_validator

from .schemas import MemoryCandidate, new_id


MemoryType = Literal[
    "core_profile",
    "preference",
    "episodic",
    "relationship",
    "shared_experience",
    "promise",
    "important_person",
    "ongoing_project",
    "recent_emotion",
    "session_summary",
]
MemoryStatus = Literal["active", "superseded", "deleted"]
ReviewStatus = Literal["accepted", "pending_review", "rejected", "duplicate"]


class MemoryExtractionInput(BaseModel):
    user_id: str
    content: str
    source_client: str
    source_session: str


class MemorySearchInput(BaseModel):
    user_id: str
    query: str
    limit: int = Field(default=5, ge=1, le=20)
    memory_types: list[MemoryType] = Field(default_factory=list)


class MemoryUpsertInput(BaseModel):
    user_id: str
    memory_type: MemoryType
    canonical_text: str = Field(min_length=1, max_length=1000)
    source_client: str
    source_session: str
    confidence: float = Field(ge=0, le=1)
    sensitivity: Literal["normal", "private", "sensitive"] = "normal"
    importance: int = Field(default=3, ge=1, le=5)
    expires_at: datetime | None = None

    @field_validator("expires_at")
    @classmethod
    def require_timezone(cls, value: datetime | None) -> datetime | None:
        if value is not None and value.tzinfo is None:
            raise ValueError("expires_at must include a timezone offset")
        return value


class MemoryRecord(BaseModel):
    memory_id: str
    user_id: str
    memory_type: MemoryType
    canonical_text: str
    source_client: str
    source_session: str
    confidence: float
    sensitivity: Literal["normal", "private", "sensitive"]
    importance: int
    status: MemoryStatus = "active"
    version: int = 1
    supersedes_memory_id: str | None = None
    expires_at: datetime | None = None
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class MemoryHit(BaseModel):
    score: float = Field(ge=0, le=1)
    record: MemoryRecord


class SessionMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class SessionSummaryInput(BaseModel):
    user_id: str
    client_id: str
    session_id: str
    messages: list[SessionMessage]


class SessionSummary(BaseModel):
    user_id: str
    client_id: str
    session_id: str
    summary: str
    message_count: int
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class MemoryReviewDecision(BaseModel):
    candidate: MemoryCandidate
    status: ReviewStatus
    reason: str
    upsert: MemoryUpsertInput | None = None


class ManualMemoryReviewRequest(BaseModel):
    user_id: str
    candidate: MemoryCandidate
    decision: Literal["accept", "reject"]
    source_client: str
    source_session: str


@runtime_checkable
class MemoryProvider(Protocol):
    async def extract_candidates(self, input: MemoryExtractionInput) -> list[MemoryCandidate]: ...
    async def search(self, input: MemorySearchInput) -> list[MemoryHit]: ...
    async def upsert(self, input: MemoryUpsertInput) -> MemoryRecord: ...
    async def supersede(self, old_id: str, next: MemoryUpsertInput) -> MemoryRecord: ...
    async def delete(self, memory_id: str) -> None: ...
    async def summarize_session(self, input: SessionSummaryInput) -> SessionSummary: ...
    async def list_records(self, user_id: str, include_deleted: bool = False) -> list[MemoryRecord]: ...


class CompanionMemoryPolicy:
    """Provider-independent rules for durable companion memory writes."""

    _credential_pattern = re.compile(
        r"(?i)(password|passphrase|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|cookie|private[_ -]?key|银行卡|身份证|密码|令牌|私钥)"
    )
    _type_map: dict[str, MemoryType] = {
        "preference": "preference",
        "identity": "core_profile",
        "project": "ongoing_project",
        "commitment": "promise",
        "relationship": "relationship",
        "routine": "preference",
        "event": "episodic",
    }

    def review(
        self,
        *,
        user_id: str,
        source_client: str,
        source_session: str,
        candidates: list[MemoryCandidate],
        existing: list[MemoryRecord],
    ) -> list[MemoryReviewDecision]:
        active_text = {_normalize(record.canonical_text) for record in existing if record.status == "active"}
        decisions: list[MemoryReviewDecision] = []
        seen: set[str] = set()
        for candidate in candidates[:3]:
            normalized = _normalize(candidate.summary)
            if not normalized or normalized in active_text or normalized in seen:
                decisions.append(MemoryReviewDecision(candidate=candidate, status="duplicate", reason="same canonical content already exists"))
                continue
            seen.add(normalized)
            if self._credential_pattern.search(candidate.summary):
                decisions.append(MemoryReviewDecision(candidate=candidate, status="rejected", reason="credential-like content is never persisted"))
                continue
            if candidate.sensitivity == "sensitive":
                decisions.append(MemoryReviewDecision(candidate=candidate, status="rejected", reason="sensitive candidate requires explicit user workflow"))
                continue
            if candidate.sensitivity == "private" or candidate.confidence < 0.75:
                decisions.append(MemoryReviewDecision(candidate=candidate, status="pending_review", reason="private or low-confidence candidate"))
                continue
            decisions.append(
                MemoryReviewDecision(
                    candidate=candidate,
                    status="accepted",
                    reason="stable, non-sensitive, high-confidence candidate",
                    upsert=self._to_upsert(
                        user_id, source_client, source_session, candidate
                    ),
                )
            )
        return decisions

    def manual_review(
        self,
        request: ManualMemoryReviewRequest,
        existing: list[MemoryRecord],
    ) -> MemoryReviewDecision:
        if request.decision == "reject":
            return MemoryReviewDecision(
                candidate=request.candidate,
                status="rejected",
                reason="explicitly rejected by user review",
            )
        automatic = self.review(
            user_id=request.user_id,
            source_client=request.source_client,
            source_session=request.source_session,
            candidates=[request.candidate],
            existing=existing,
        )[0]
        if automatic.status in {"rejected", "duplicate"}:
            return automatic
        return MemoryReviewDecision(
            candidate=request.candidate,
            status="accepted",
            reason="explicitly accepted by user review",
            upsert=self._to_upsert(
                request.user_id,
                request.source_client,
                request.source_session,
                request.candidate,
            ),
        )

    def _to_upsert(
        self,
        user_id: str,
        source_client: str,
        source_session: str,
        candidate: MemoryCandidate,
    ) -> MemoryUpsertInput:
        memory_type = self._type_map[candidate.type]
        importance = 4 if memory_type in {"promise", "relationship", "ongoing_project"} else 3
        return MemoryUpsertInput(
            user_id=user_id,
            memory_type=memory_type,
            canonical_text=candidate.summary,
            source_client=source_client,
            source_session=source_session,
            confidence=candidate.confidence,
            sensitivity=candidate.sensitivity,
            importance=importance,
        )


class FakeMemoryProvider:
    """Deterministic in-memory implementation for local and contract tests."""

    def __init__(self) -> None:
        self.records: dict[str, MemoryRecord] = {}

    async def extract_candidates(self, input: MemoryExtractionInput) -> list[MemoryCandidate]:
        if re.search(r"我喜欢|我不喜欢|my preference", input.content, re.IGNORECASE):
            return [MemoryCandidate(type="preference", summary=input.content.strip(), confidence=0.8)]
        return []

    async def search(self, input: MemorySearchInput) -> list[MemoryHit]:
        now = datetime.now(timezone.utc)
        query_terms = _terms(input.query)
        hits: list[MemoryHit] = []
        for record in self.records.values():
            if record.user_id != input.user_id or record.status != "active":
                continue
            if record.expires_at and record.expires_at <= now:
                continue
            if input.memory_types and record.memory_type not in input.memory_types:
                continue
            record_terms = _terms(record.canonical_text)
            overlap = len(query_terms & record_terms) / max(len(query_terms), 1)
            score = min(1.0, 0.45 + overlap * 0.35 + record.importance * 0.04)
            hits.append(MemoryHit(score=score, record=record))
        hits.sort(key=lambda hit: (hit.score, hit.record.updated_at), reverse=True)
        return hits[: input.limit]

    async def upsert(self, input: MemoryUpsertInput) -> MemoryRecord:
        normalized = _normalize(input.canonical_text)
        for record in self.records.values():
            if record.user_id == input.user_id and record.status == "active" and _normalize(record.canonical_text) == normalized:
                record.updated_at = datetime.now(timezone.utc)
                record.confidence = max(record.confidence, input.confidence)
                return record
        record = MemoryRecord(memory_id=new_id("mem"), **input.model_dump())
        self.records[record.memory_id] = record
        return record

    async def supersede(self, old_id: str, next: MemoryUpsertInput) -> MemoryRecord:
        old = self.records.get(old_id)
        if old is None or old.status != "active":
            raise KeyError(f"active memory not found: {old_id}")
        if old.user_id != next.user_id:
            raise ValueError("cannot supersede memory owned by another user")
        old.status = "superseded"
        old.updated_at = datetime.now(timezone.utc)
        record = MemoryRecord(
            memory_id=new_id("mem"),
            version=old.version + 1,
            supersedes_memory_id=old.memory_id,
            **next.model_dump(),
        )
        self.records[record.memory_id] = record
        return record

    async def delete(self, memory_id: str) -> None:
        record = self.records.get(memory_id)
        if record is None:
            raise KeyError(f"memory not found: {memory_id}")
        record.status = "deleted"
        record.updated_at = datetime.now(timezone.utc)

    async def summarize_session(self, input: SessionSummaryInput) -> SessionSummary:
        content = " ".join(message.content.strip() for message in input.messages if message.content.strip())
        return SessionSummary(
            user_id=input.user_id,
            client_id=input.client_id,
            session_id=input.session_id,
            summary=content[:1000],
            message_count=len(input.messages),
        )

    async def list_records(self, user_id: str, include_deleted: bool = False) -> list[MemoryRecord]:
        records = [record for record in self.records.values() if record.user_id == user_id]
        if not include_deleted:
            records = [record for record in records if record.status != "deleted"]
        return sorted(records, key=lambda record: record.updated_at, reverse=True)

    def reset(self) -> None:
        self.records.clear()


class SessionContextStore:
    """Bounded short-term context keyed by user, client and session."""

    def __init__(self, max_messages: int = 20) -> None:
        self.max_messages = max_messages
        self._messages: dict[tuple[str, str, str], deque[SessionMessage]] = defaultdict(
            lambda: deque(maxlen=self.max_messages)
        )

    @staticmethod
    def key(user_id: str, client_id: str, session_id: str) -> tuple[str, str, str]:
        return user_id, client_id, session_id

    def append(self, user_id: str, client_id: str, session_id: str, message: SessionMessage) -> None:
        self._messages[self.key(user_id, client_id, session_id)].append(message)

    def recent(self, user_id: str, client_id: str, session_id: str) -> list[SessionMessage]:
        return list(self._messages.get(self.key(user_id, client_id, session_id), ()))

    def clear(self) -> None:
        self._messages.clear()


def _normalize(value: str) -> str:
    return " ".join(value.casefold().split())


def _terms(value: str) -> set[str]:
    return {term.casefold() for term in re.findall(r"[\w\u4e00-\u9fff]+", value) if term}
