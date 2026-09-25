from __future__ import annotations

import hashlib
import json
import re

from .enums import ConflictAction, MemoryType
from .models import ConflictResolution, MemoryCandidateCreate, MemoryItem


STRUCTURED_KEY_TYPES = frozenset(
    {
        MemoryType.USER_PROFILE,
        MemoryType.USER_PREFERENCE,
        MemoryType.IMPORTANT_PERSON,
        MemoryType.LONG_TERM_PROJECT,
        MemoryType.COMMITMENT,
        MemoryType.RELATIONSHIP_FACT,
        MemoryType.RECURRING_HABIT,
        MemoryType.CORRECTED_FACT,
    }
)


def normalize_text(value: str) -> str:
    return " ".join(value.casefold().split())


def canonical_key(candidate: MemoryCandidateCreate) -> str | None:
    explicit = candidate.content_json.get("canonical_key")
    if isinstance(explicit, str) and explicit.strip():
        return explicit.strip().casefold()[:500]
    if candidate.memory_type not in STRUCTURED_KEY_TYPES:
        return None
    subject = candidate.content_json.get("subject")
    predicate = candidate.content_json.get("predicate")
    if not isinstance(subject, str) or not isinstance(predicate, str):
        return None
    identity = json.dumps(
        [candidate.memory_type.value, normalize_text(subject), normalize_text(predicate)],
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()


def token_terms(value: str) -> set[str]:
    return {
        token.casefold()
        for token in re.findall(r"[\w\u3040-\u30ff\u3400-\u9fff]+", value)
        if token
    }


def lexical_similarity(left: str, right: str) -> float:
    left_terms = token_terms(left)
    right_terms = token_terms(right)
    if not left_terms or not right_terms:
        return 1.0 if normalize_text(left) == normalize_text(right) else 0.0
    return len(left_terms & right_terms) / len(left_terms | right_terms)


class ConflictResolver:
    def __init__(self, *, duplicate_threshold: float = 0.92) -> None:
        self.duplicate_threshold = duplicate_threshold

    def resolve(
        self,
        candidate: MemoryCandidateCreate,
        existing: list[MemoryItem],
        *,
        semantic_scores: dict[object, float] | None = None,
    ) -> ConflictResolution:
        semantic_scores = semantic_scores or {}
        normalized = normalize_text(candidate.content_text)
        key = canonical_key(candidate)
        for item in existing:
            version = item.current_version
            if version is None:
                continue
            current = normalize_text(version.content_text)
            if normalized == current:
                return ConflictResolution(
                    action=ConflictAction.DUPLICATE,
                    reason="same_normalized_content",
                    existing_memory_id=item.memory_id,
                    existing_version_id=version.version_id,
                    similarity=1.0,
                )
            similarity = lexical_similarity(candidate.content_text, version.content_text)
            if key and item.canonical_key == key:
                return ConflictResolution(
                    action=ConflictAction.SUPERSEDE,
                    reason="same_structured_key_with_changed_value",
                    existing_memory_id=item.memory_id,
                    existing_version_id=version.version_id,
                    similarity=similarity,
                )
            if similarity >= self.duplicate_threshold:
                return ConflictResolution(
                    action=ConflictAction.DUPLICATE,
                    reason="high_lexical_similarity",
                    existing_memory_id=item.memory_id,
                    existing_version_id=version.version_id,
                    similarity=similarity,
                )
            semantic_similarity = max(
                0.0,
                min(1.0, float(semantic_scores.get(item.memory_id, 0.0))),
            )
            if semantic_similarity >= self.duplicate_threshold:
                return ConflictResolution(
                    action=ConflictAction.DUPLICATE,
                    reason="high_semantic_similarity",
                    existing_memory_id=item.memory_id,
                    existing_version_id=version.version_id,
                    similarity=semantic_similarity,
                )
        return ConflictResolution(action=ConflictAction.CREATE, reason="no_conflict")
