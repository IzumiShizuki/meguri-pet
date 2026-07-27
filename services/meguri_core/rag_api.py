"""DashScope-backed canonical Lore RAG with a versioned local vector artifact."""

from __future__ import annotations

import asyncio
import hashlib
import inspect
import json
import math
import os
from pathlib import Path
import re
from typing import Any, Sequence

from .config import BUILD_ID, DATA_ROOT
from .memory_service.embedding import EmbeddingProvider, create_runtime_embedding_provider
from .memory_service.rerank import DashScopeRerankProvider, create_runtime_rerank_provider
from .providers import MockRagProvider
from .schemas import RuntimeState


RAG_VECTOR_ARTIFACT_VERSION = 1
DEFAULT_RAG_VECTOR_FILE = "dashscope_vectors.json"


def _language_for(query: str) -> str:
    return "ja" if re.search(r"[\u3040-\u30ff]", query) else "zh"


def _row_text(row: dict[str, Any], language: str) -> str:
    preferred = row.get("text_jp") if language == "ja" else row.get("text_zh")
    fallback = row.get("text_zh") if language == "ja" else row.get("text_jp")
    return str(preferred or fallback or row.get("text") or row.get("content") or row.get("response") or "").strip()


def _cosine(left: Sequence[float], right: Sequence[float]) -> float:
    if len(left) != len(right) or not left:
        return -1.0
    dot = sum(a * b for a, b in zip(left, right, strict=True))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0 or right_norm == 0:
        return -1.0
    return dot / (left_norm * right_norm)


class DashScopeRagProvider:
    """Shared Lore RAG used by AstrBot and all other Core clients.

    Canonical chunks are embedded once through the provider API and stored in a
    versioned data artifact.  At request time only the query is embedded; the
    semantic shortlist is then reranked remotely.  Relationship filtering is
    deliberately performed before either API call.
    """

    def __init__(
        self,
        data_root: Path,
        *,
        embedding_provider: EmbeddingProvider,
        rerank_provider: DashScopeRerankProvider,
        artifact_path: Path | None = None,
        candidate_limit: int = 20,
        fallback: MockRagProvider | None = None,
    ) -> None:
        if candidate_limit < 3:
            raise ValueError("RAG candidate limit must be at least 3")
        self.data_root = data_root
        self.embedding_provider = embedding_provider
        self.rerank_provider = rerank_provider
        self.candidate_limit = candidate_limit
        self.fallback = fallback or MockRagProvider(data_root)
        self.rows = self.fallback.rows
        self.artifact_path = artifact_path or (
            data_root / "exports" / "rag" / DEFAULT_RAG_VECTOR_FILE
        )
        self._vectors = self._load_vectors()

    def _load_vectors(self) -> dict[tuple[int, str], list[float]]:
        try:
            payload = json.loads(self.artifact_path.read_text(encoding="utf-8"))
            meta = payload["metadata"]
            if meta.get("artifact_version") != RAG_VECTOR_ARTIFACT_VERSION:
                raise ValueError("artifact version")
            if meta.get("build_id") != BUILD_ID:
                raise ValueError("build id")
            if meta.get("embedding_model") != self.embedding_provider.model:
                raise ValueError("embedding model")
            if meta.get("embedding_revision") != self.embedding_provider.revision:
                raise ValueError("embedding revision")
            if int(meta.get("embedding_dimension")) != self.embedding_provider.dimension:
                raise ValueError("embedding dimension")
            values: dict[tuple[int, str], list[float]] = {}
            for item in payload["vectors"]:
                row_index = int(item["row_index"])
                language = str(item["language"])
                vector = [float(value) for value in item["vector"]]
                if row_index < 0 or row_index >= len(self.rows) or language not in {"zh", "ja"}:
                    raise ValueError("row identity")
                if len(vector) != self.embedding_provider.dimension or not all(math.isfinite(value) for value in vector):
                    raise ValueError("vector payload")
                current_text = _row_text(self.rows[row_index], language)
                if item.get("content_sha256") != hashlib.sha256(current_text.encode("utf-8")).hexdigest():
                    raise ValueError("source content changed")
                values[(row_index, language)] = vector
            expected = {
                (index, language)
                for index, row in enumerate(self.rows)
                for language in ("zh", "ja")
                if _row_text(row, language)
            }
            if values.keys() != expected:
                raise ValueError("incomplete vector set")
            return values
        except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise RuntimeError(
                "DashScope RAG vector artifact is unavailable or does not match the active release"
            ) from exc

    async def search(self, query: str, state: RuntimeState, limit: int = 3) -> list[str]:
        if limit <= 0 or not query.strip() or state is None:
            return []
        relationship = str(state.relationship_profile or "").strip()
        if not relationship:
            return []
        language = _language_for(query)
        try:
            query_vector = (await self.embedding_provider.embed([query]))[0]
            candidates: list[tuple[float, int, str]] = []
            for index, row in enumerate(self.rows):
                if str(row.get("relationship_stage") or "") != relationship:
                    continue
                text = _row_text(row, language)
                vector = self._vectors.get((index, language))
                if text and vector is not None:
                    similarity = _cosine(query_vector, vector)
                    if similarity > 0.0:
                        candidates.append((similarity, index, text))
            candidates.sort(key=lambda item: (-item[0], item[1]))
            shortlist = candidates[: self.candidate_limit]
            if not shortlist:
                return self.fallback.search(query, state, limit)
            reranked = await self.rerank_provider.rerank(
                query,
                [item[2] for item in shortlist],
                top_n=min(limit, len(shortlist)),
            )
            vector_ranking = [
                (f"row:{item[1]}:{language}", item[2]) for item in shortlist
            ]
            rerank_ranking = [
                (f"row:{shortlist[result.index][1]}:{language}", shortlist[result.index][2])
                for result in reranked
                if result.relevance_score > 0.0
                and 0 <= result.index < len(shortlist)
            ]
            lexical_ranking = [
                (f"row:{row_index}:{language}", text)
                for row_index, text in self.fallback.search_with_ids(
                query, state, min(self.candidate_limit, max(limit, 3))
                )
            ]
            return _rrf_merge_items(
                [rerank_ranking, vector_ranking, lexical_ranking],
                limit=limit,
            )
        except Exception:
            # Chat availability remains independent of a transient retrieval API
            # outage.  This fallback is observable through health metadata and
            # preserves the prior relevance-gated canonical behavior.
            return self.fallback.search(query, state, limit)


def _dedupe_and_limit(values: Sequence[str], limit: int) -> list[str]:
    selected: list[str] = []
    seen: set[str] = set()
    for value in values:
        normalized = " ".join(value.split()).casefold()
        if not normalized or normalized in seen:
            continue
        selected.append(value[:500].strip())
        seen.add(normalized)
        if len(selected) >= limit:
            break
    return selected


def _rrf_merge(
    rankings: Sequence[Sequence[str]], *, limit: int, rank_constant: int = 60
) -> list[str]:
    """Fuse provider, vector, and keyword ranks without mixing score scales."""

    return _rrf_merge_items(
        [[(" ".join(str(value).split()).casefold(), str(value)) for value in ranking]
         for ranking in rankings],
        limit=limit,
        rank_constant=rank_constant,
    )


def _rrf_merge_items(
    rankings: Sequence[Sequence[tuple[str, str]]],
    *,
    limit: int,
    rank_constant: int = 60,
) -> list[str]:
    """Fuse rankings by stable source identity while retaining display text."""

    if limit <= 0 or rank_constant < 1:
        return []
    scores: dict[str, float] = {}
    values: dict[str, str] = {}
    first_seen: dict[str, int] = {}
    seen_order = 0
    for ranking in rankings:
        seen_in_ranking: set[str] = set()
        for rank, (raw_key, raw_value) in enumerate(ranking, start=1):
            value = str(raw_value).strip()
            key = str(raw_key).strip().casefold()
            if not key or key in seen_in_ranking:
                continue
            seen_in_ranking.add(key)
            if key not in first_seen:
                first_seen[key] = seen_order
                seen_order += 1
                values[key] = value[:500]
            scores[key] = scores.get(key, 0.0) + 1.0 / (rank_constant + rank)
    ordered = sorted(scores, key=lambda key: (-scores[key], first_seen[key], key))
    return [values[key] for key in ordered[:limit]]


async def build_dashscope_rag_vectors(
    data_root: Path,
    *,
    embedding_provider: EmbeddingProvider,
    output_path: Path | None = None,
    batch_size: int = 10,
) -> Path:
    # The OpenAI-compatible endpoint accepts a smaller practical batch than
    # the native Model Studio endpoint; keep this limit explicit so a corpus
    # build cannot fail midway with an opaque provider 400 response.
    if batch_size < 1 or batch_size > 10:
        raise ValueError("RAG vector build batch size must be within 1..10")
    corpus = MockRagProvider(data_root)
    entries: list[tuple[int, str, str]] = [
        (index, language, text)
        for index, row in enumerate(corpus.rows)
        for language in ("zh", "ja")
        if (text := _row_text(row, language))
    ]
    vectors: list[dict[str, Any]] = []
    for start in range(0, len(entries), batch_size):
        batch = entries[start : start + batch_size]
        values = await embedding_provider.embed([item[2] for item in batch])
        for (row_index, language, text), vector in zip(batch, values, strict=True):
            vectors.append(
                {
                    "row_index": row_index,
                    "language": language,
                    "content_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                    "vector": vector,
                }
            )
    target = output_path or data_root / "exports" / "rag" / DEFAULT_RAG_VECTOR_FILE
    target.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "metadata": {
            "artifact_version": RAG_VECTOR_ARTIFACT_VERSION,
            "build_id": BUILD_ID,
            "embedding_model": embedding_provider.model,
            "embedding_revision": embedding_provider.revision,
            "embedding_dimension": embedding_provider.dimension,
            "source_rows": len(corpus.rows),
        },
        "vectors": vectors,
    }
    temp = target.with_suffix(target.suffix + ".tmp")
    temp.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    temp.replace(target)
    return target


def create_rag_provider_from_env(
    data_root: Path = DATA_ROOT,
    *,
    env: dict[str, str] | None = None,
) -> MockRagProvider | DashScopeRagProvider:
    values = os.environ if env is None else env
    backend = values.get("MEGURI_RAG_BACKEND", "lexical").strip().casefold()
    if backend in {"", "lexical"}:
        return MockRagProvider(data_root)
    if backend != "dashscope":
        raise RuntimeError("MEGURI_RAG_BACKEND must be dashscope or lexical")
    embedding = create_runtime_embedding_provider(env=values)
    reranker = create_runtime_rerank_provider(env=values)
    if embedding is None or reranker is None:
        raise RuntimeError("DashScope RAG requires embedding and rerank providers")
    artifact_raw = values.get("MEGURI_RAG_VECTOR_ARTIFACT", "").strip()
    artifact_path = Path(artifact_raw) if artifact_raw else None
    if artifact_path is not None and not artifact_path.is_absolute():
        artifact_path = data_root / "exports" / "rag" / artifact_path
    return DashScopeRagProvider(
        data_root,
        embedding_provider=embedding,
        rerank_provider=reranker,
        artifact_path=artifact_path,
        candidate_limit=int(values.get("MEGURI_RAG_CANDIDATE_LIMIT", "20")),
    )
