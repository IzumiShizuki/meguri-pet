from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import math

from .enums import MemoryStatus, MemoryVersionStatus
from .models import MemoryHit, MemoryItem, MemoryScoreComponents
from .token_budget import take_within_token_budget


@dataclass(frozen=True)
class RetrievalWeights:
    semantic: float = 0.45
    keyword: float = 0.15
    importance: float = 0.15
    confidence: float = 0.15
    recency: float = 0.10

    def __post_init__(self) -> None:
        total = self.semantic + self.keyword + self.importance + self.confidence + self.recency
        if not math.isclose(total, 1.0, rel_tol=1e-6):
            raise ValueError("retrieval weights must sum to 1")


def recency_score(updated_at: datetime, now: datetime, half_life_days: float = 180) -> float:
    if updated_at.tzinfo is None or now.tzinfo is None:
        raise ValueError("recency timestamps must include timezone offsets")
    age_days = max(0.0, (now - updated_at).total_seconds() / 86400)
    return math.exp(-math.log(2) * age_days / half_life_days)


def is_recallable_item(item: MemoryItem, *, now: datetime) -> bool:
    version = item.current_version
    return bool(
        item.status is MemoryStatus.ACTIVE
        and item.deleted_at is None
        and version is not None
        and version.status is MemoryVersionStatus.ACTIVE
        and (item.effective_at is None or item.effective_at <= now)
        and (item.expires_at is None or item.expires_at > now)
    )


def build_hit(
    item: MemoryItem,
    *,
    semantic: float,
    keyword: float,
    now: datetime | None = None,
    weights: RetrievalWeights | None = None,
) -> MemoryHit:
    if item.current_version is None:
        raise ValueError("retrieval requires an item with its current version")
    weights = weights or RetrievalWeights()
    now = now or datetime.now(timezone.utc)
    if not is_recallable_item(item, now=now):
        raise ValueError("retrieval requires an active, effective memory version")
    components = MemoryScoreComponents(
        semantic=max(0.0, min(1.0, semantic)),
        keyword=max(0.0, min(1.0, keyword)),
        importance=item.importance,
        confidence=item.confidence,
        recency=recency_score(item.updated_at, now),
    )
    score = (
        components.semantic * weights.semantic
        + components.keyword * weights.keyword
        + components.importance * weights.importance
        + components.confidence * weights.confidence
        + components.recency * weights.recency
    )
    version = item.current_version
    return MemoryHit(
        memory_id=item.memory_id, version_id=version.version_id,
        memory_type=item.memory_type, content_text=version.content_text,
        score=min(1.0, score), score_components=components,
        provenance=version.provenance, created_at=item.created_at,
        updated_at=item.updated_at,
    )


def weighted_rrf_hits(
    candidates: list[tuple[MemoryItem, float, float]],
    *,
    now: datetime | None = None,
    weights: RetrievalWeights | None = None,
    rank_constant: int = 60,
) -> list[MemoryHit]:
    """Fuse heterogeneous channels by rank without comparing raw score scales."""
    if rank_constant < 1:
        raise ValueError("rank_constant must be positive")
    weights = weights or RetrievalWeights()
    now = now or datetime.now(timezone.utc)
    by_id: dict[object, tuple[MemoryItem, MemoryScoreComponents]] = {}
    for item, semantic, keyword in candidates:
        if not is_recallable_item(item, now=now):
            continue
        components = MemoryScoreComponents(
            semantic=max(0.0, min(1.0, semantic)),
            keyword=max(0.0, min(1.0, keyword)),
            importance=item.importance,
            confidence=item.confidence,
            recency=recency_score(item.updated_at, now),
        )
        previous = by_id.get(item.memory_id)
        if previous is None:
            by_id[item.memory_id] = (item, components)
        else:
            old = previous[1]
            by_id[item.memory_id] = (
                item,
                components.model_copy(update={
                    "semantic": max(old.semantic, components.semantic),
                    "keyword": max(old.keyword, components.keyword),
                }),
            )
    channels = {
        "semantic": weights.semantic, "keyword": weights.keyword,
        "importance": weights.importance, "confidence": weights.confidence,
        "recency": weights.recency,
    }
    fused = {memory_id: 0.0 for memory_id in by_id}
    for channel, weight in channels.items():
        ranked = sorted(
            by_id.items(),
            key=lambda pair: (getattr(pair[1][1], channel), str(pair[0])),
            reverse=True,
        )
        for rank, (memory_id, _) in enumerate(ranked, 1):
            fused[memory_id] += weight / (rank_constant + rank)
    maximum = 1.0 / (rank_constant + 1)
    return [
        MemoryHit(
            memory_id=item.memory_id, version_id=item.current_version.version_id,
            memory_type=item.memory_type, content_text=item.current_version.content_text,
            score=min(1.0, fused[memory_id] / maximum), score_components=components,
            provenance=item.current_version.provenance, created_at=item.created_at,
            updated_at=item.updated_at,
        )
        for memory_id, (item, components) in by_id.items()
    ]


def rerank_and_budget(hits: list[MemoryHit], *, token_budget: int, limit: int) -> list[MemoryHit]:
    best_by_memory: dict[object, MemoryHit] = {}
    for hit in hits:
        previous = best_by_memory.get(hit.memory_id)
        if previous is None or hit.score > previous.score:
            best_by_memory[hit.memory_id] = hit
    ordered = sorted(
        best_by_memory.values(),
        key=lambda hit: (hit.score, hit.updated_at, str(hit.memory_id)),
        reverse=True,
    )[:limit]
    return take_within_token_budget(
        ordered,
        text_of=lambda hit: hit.content_text,
        token_budget=token_budget,
    )
