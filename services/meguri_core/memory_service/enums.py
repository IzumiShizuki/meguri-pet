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


class RiskLevel(StrEnum):
    LOW = "low"
    MODERATE = "moderate"
    HIGH = "high"
    PROHIBITED = "prohibited"


class MergePolicy(StrEnum):
    REPLACE = "replace"
    SET_UNION = "set_union"
    SET_REMOVE = "set_remove"
    CONFIRM = "confirm"
    STATE_TRANSITION = "state_transition"
    THREE_WAY_MERGE = "three_way_merge"
    MANUAL = "manual"
    # Legacy values remain readable while old candidates are drained.
    REVIEW = "review"
    CREATE_ONLY = "create_only"
    SUPERSEDE = "supersede"


class MemoryScope(StrEnum):
    GLOBAL_USER = "global_user"
    CLIENT_PRIVATE = "client_private"


class CandidateStatus(StrEnum):
    PENDING = "pending"
    AUTO_APPROVED = "auto_approved"
    NEEDS_CONFIRMATION = "needs_confirmation"
    PENDING_REVIEW = "pending_review"
    PROCESSING = "processing"
    APPROVED = "approved"
    REJECTED = "rejected"
    EXPIRED = "expired"


class MemoryStatus(StrEnum):
    ACTIVE = "active"
    CONFLICTED = "conflicted"
    SUPERSEDED = "superseded"
    EXPIRED = "expired"
    ARCHIVED = "archived"
    DELETED = "deleted"


class MemoryVersionStatus(StrEnum):
    ACTIVE = "active"
    SUPERSEDED = "superseded"
    CONFLICT_BRANCH = "conflict_branch"
    TOMBSTONE = "tombstone"


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
    USER_MANUAL = "user_manual"
    DIRECT_USER = "direct_user"
    REPEATED_EVIDENCE = "repeated_evidence"
    LOCAL_EDIT = "local_edit"
    SUMMARY = "summary"
    EXTERNAL = "external"
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
    CONFLICT = "conflict"
    PROJECTION_REPAIR = "projection_repair"


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
    CandidateStatus.PENDING: frozenset(
        {CandidateStatus.AUTO_APPROVED, CandidateStatus.NEEDS_CONFIRMATION,
         CandidateStatus.APPROVED, CandidateStatus.REJECTED, CandidateStatus.EXPIRED}
    ),
    CandidateStatus.AUTO_APPROVED: frozenset(
        {CandidateStatus.APPROVED, CandidateStatus.NEEDS_CONFIRMATION}
    ),
    CandidateStatus.NEEDS_CONFIRMATION: frozenset(
        {CandidateStatus.APPROVED, CandidateStatus.REJECTED, CandidateStatus.EXPIRED}
    ),
    CandidateStatus.PENDING_REVIEW: frozenset(
        {
            CandidateStatus.PROCESSING,
            CandidateStatus.APPROVED,
            CandidateStatus.REJECTED,
            CandidateStatus.EXPIRED,
        }
    ),
    CandidateStatus.PROCESSING: frozenset(
        {
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
            MemoryStatus.CONFLICTED,
        }
    ),
    MemoryStatus.SUPERSEDED: frozenset({MemoryStatus.DELETED}),
    MemoryStatus.EXPIRED: frozenset({MemoryStatus.ACTIVE, MemoryStatus.DELETED}),
    MemoryStatus.ARCHIVED: frozenset({MemoryStatus.ACTIVE, MemoryStatus.DELETED}),
    MemoryStatus.DELETED: frozenset({MemoryStatus.ACTIVE}),
    MemoryStatus.CONFLICTED: frozenset({MemoryStatus.ACTIVE, MemoryStatus.DELETED}),
}


def candidate_transition_allowed(current: CandidateStatus, target: CandidateStatus) -> bool:
    return target in CANDIDATE_TRANSITIONS[current]


def memory_transition_allowed(current: MemoryStatus, target: MemoryStatus) -> bool:
    return target in MEMORY_TRANSITIONS[current]


VERSION_TRANSITIONS: dict[MemoryVersionStatus, frozenset[MemoryVersionStatus]] = {
    MemoryVersionStatus.ACTIVE: frozenset({MemoryVersionStatus.SUPERSEDED}),
    MemoryVersionStatus.SUPERSEDED: frozenset(),
    MemoryVersionStatus.CONFLICT_BRANCH: frozenset(),
    MemoryVersionStatus.TOMBSTONE: frozenset(),
}


def version_transition_allowed(
    current: MemoryVersionStatus, target: MemoryVersionStatus
) -> bool:
    return target in VERSION_TRANSITIONS[current]
