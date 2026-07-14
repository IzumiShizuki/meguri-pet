from __future__ import annotations

from enum import StrEnum


class MemoryType(StrEnum):
    USER_PROFILE = "user_profile"
    USER_PREFERENCE = "user_preference"
    IMPORTANT_PERSON = "important_person"
    LONG_TERM_PROJECT = "long_term_project"
    COMMITMENT = "commitment"
    RELATIONSHIP_FACT = "relationship_fact"
    RECURRING_HABIT = "recurring_habit"
    CORRECTED_FACT = "corrected_fact"


class Sensitivity(StrEnum):
    NORMAL = "normal"
    PRIVATE = "private"
    SENSITIVE = "sensitive"


class MemoryScope(StrEnum):
    GLOBAL_USER = "global_user"
    CLIENT_PRIVATE = "client_private"


class CandidateStatus(StrEnum):
    PENDING_REVIEW = "pending_review"
    PROCESSING = "processing"
    APPROVED = "approved"
    REJECTED = "rejected"
    EXPIRED = "expired"


class MemoryStatus(StrEnum):
    ACTIVE = "active"
    SUPERSEDED = "superseded"
    EXPIRED = "expired"
    ARCHIVED = "archived"
    DELETED = "deleted"


class ReviewDecision(StrEnum):
    APPROVE = "approve"
    REJECT = "reject"


class IdentityBindingStatus(StrEnum):
    PENDING_VERIFICATION = "pending_verification"
    ACTIVE = "active"
    UNBOUND = "unbound"


class ActorType(StrEnum):
    USER = "user"
    ADMIN = "admin"
    POLICY = "policy"
    SYSTEM = "system"
    IMPORT = "import"


class SourceKind(StrEnum):
    DIRECT_USER = "direct_user"
    LLM_CANDIDATE = "llm_candidate"
    MEMORYOS_IMPORT = "memoryos_import"
    MEM0_SHADOW = "mem0_shadow"
    ADMIN = "admin"


class EmbeddingStatus(StrEnum):
    READY = "ready"
    FAILED = "failed"
    STALE = "stale"


class OutboxStatus(StrEnum):
    PENDING = "pending"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"
    DEAD_LETTER = "dead_letter"


class FeedbackKind(StrEnum):
    HELPFUL = "helpful"
    WRONG = "wrong"
    STALE = "stale"
    CORRECTED = "corrected"
    USER_DELETED = "user_deleted"
    FALSE_RECALL = "false_recall"


class AuditAction(StrEnum):
    CANDIDATE_CREATE = "candidate_create"
    APPROVE = "approve"
    REJECT = "reject"
    ITEM_CREATE = "item_create"
    SUPERSEDE = "supersede"
    DELETE = "delete"
    RESTORE = "restore"
    EXPORT = "export"
    HARD_DELETE = "hard_delete"
    IDENTITY_BIND = "identity_bind"
    IDENTITY_UNBIND = "identity_unbind"


class ConflictAction(StrEnum):
    CREATE = "create"
    DUPLICATE = "duplicate"
    MERGE = "merge"
    SUPERSEDE = "supersede"
    REJECT = "reject"


class SearchMode(StrEnum):
    STRUCTURED = "structured"
    KEYWORD = "keyword"
    EXACT_VECTOR = "exact_vector"
    HYBRID = "hybrid"


CANDIDATE_TRANSITIONS: dict[CandidateStatus, frozenset[CandidateStatus]] = {
    CandidateStatus.PENDING_REVIEW: frozenset(
        {
            CandidateStatus.PROCESSING,
            CandidateStatus.REJECTED,
            CandidateStatus.EXPIRED,
        }
    ),
    CandidateStatus.PROCESSING: frozenset(
        {
            CandidateStatus.PENDING_REVIEW,
            CandidateStatus.APPROVED,
            CandidateStatus.REJECTED,
        }
    ),
    CandidateStatus.APPROVED: frozenset(),
    CandidateStatus.REJECTED: frozenset(),
    CandidateStatus.EXPIRED: frozenset(),
}


MEMORY_TRANSITIONS: dict[MemoryStatus, frozenset[MemoryStatus]] = {
    MemoryStatus.ACTIVE: frozenset(
        {
            MemoryStatus.SUPERSEDED,
            MemoryStatus.EXPIRED,
            MemoryStatus.ARCHIVED,
            MemoryStatus.DELETED,
        }
    ),
    MemoryStatus.SUPERSEDED: frozenset({MemoryStatus.DELETED}),
    MemoryStatus.EXPIRED: frozenset({MemoryStatus.ACTIVE, MemoryStatus.DELETED}),
    MemoryStatus.ARCHIVED: frozenset({MemoryStatus.ACTIVE, MemoryStatus.DELETED}),
    MemoryStatus.DELETED: frozenset({MemoryStatus.ACTIVE}),
}


def candidate_transition_allowed(current: CandidateStatus, target: CandidateStatus) -> bool:
    return target in CANDIDATE_TRANSITIONS[current]


def memory_transition_allowed(current: MemoryStatus, target: MemoryStatus) -> bool:
    return target in MEMORY_TRANSITIONS[current]
