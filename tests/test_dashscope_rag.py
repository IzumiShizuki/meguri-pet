from __future__ import annotations

import asyncio
import json
from pathlib import Path

import httpx
import pytest

from services.meguri_core.memory_service.embedding import DashScopeEmbeddingProvider
from services.meguri_core.memory_service.rerank import DashScopeRerankProvider, RerankResult
from services.meguri_core.rag_api import (
    DashScopeRagProvider,
    _rrf_merge,
    _rrf_merge_items,
    build_dashscope_rag_vectors,
)
from services.meguri_core.schemas import RuntimeState


@pytest.mark.asyncio
async def test_dashscope_embedding_uses_openai_compatible_batch_contract() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url == httpx.URL("https://example.test/v1/embeddings")
        assert request.headers["Authorization"] == "Bearer test-key"
        body = json.loads(request.content)
        assert body == {
            "model": "text-embedding-v4",
            "input": ["甲", "乙"],
            "dimensions": 3,
        }
        return httpx.Response(
            200,
            json={
                "data": [
                    {"index": 1, "embedding": [0.0, 1.0, 0.0]},
                    {"index": 0, "embedding": [1.0, 0.0, 0.0]},
                ]
            },
        )

    provider = DashScopeEmbeddingProvider(
        base_url="https://example.test/v1",
        api_key="test-key",
        model="text-embedding-v4",
        revision="dashscope-r1",
        dimension=3,
        transport=httpx.MockTransport(handler),
    )

    assert await provider.embed(["甲", "乙"]) == [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0]]


@pytest.mark.asyncio
async def test_dashscope_rerank_uses_native_model_studio_contract() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url == httpx.URL(
            "https://example.test/api/v1/services/rerank/text-rerank/text-rerank"
        )
        body = json.loads(request.content)
        assert body["model"] == "qwen3-rerank"
        assert body["input"] == {"query": "query", "documents": ["a", "b"]}
        return httpx.Response(
            200,
            json={"output": {"results": [{"index": 1, "relevance_score": 0.9}]}},
        )

    provider = DashScopeRerankProvider(
        endpoint="https://example.test/api/v1/services/rerank/text-rerank/text-rerank",
        api_key="test-key",
        transport=httpx.MockTransport(handler),
    )

    assert await provider.rerank("query", ["a", "b"], top_n=1) == [
        RerankResult(index=1, relevance_score=0.9)
    ]


class _Embedding:
    model = "text-embedding-v4"
    revision = "dashscope-r1"
    dimension = 3

    async def embed(self, texts):
        # Deterministic non-zero vectors are enough to validate artifact wiring.
        return [[1.0, float(index + 1), 0.5] for index, _ in enumerate(texts)]


class _Reranker:
    async def rerank(self, query, documents, *, top_n):
        return [RerankResult(index=index, relevance_score=1.0 - index / 10) for index in range(top_n)]


def test_rrf_fuses_rankings_without_relying_on_incompatible_scores() -> None:
    assert _rrf_merge(
        [
            ["vector-only", "shared", "duplicate"],
            ["shared", "keyword-only", "duplicate", "duplicate"],
            ["shared", "rerank-only"],
        ],
        limit=4,
    ) == ["shared", "duplicate", "vector-only", "keyword-only"]


def test_rrf_uses_source_identity_when_keyword_text_is_a_snippet() -> None:
    assert _rrf_merge_items(
        [
            [("row:1:zh", "complete canonical row")],
            [("row:1:zh", "short snippet")],
        ],
        limit=2,
    ) == ["complete canonical row"]


@pytest.mark.asyncio
async def test_versioned_dashscope_artifact_drives_shared_lore_retrieval(tmp_path: Path) -> None:
    source_root = Path("datasets/meguri")
    artifact = tmp_path / "dashscope_vectors.json"
    provider = _Embedding()
    await build_dashscope_rag_vectors(
        source_root,
        embedding_provider=provider,
        output_path=artifact,
        batch_size=10,
    )
    rag = DashScopeRagProvider(
        source_root,
        embedding_provider=provider,
        rerank_provider=_Reranker(),  # type: ignore[arg-type]
        artifact_path=artifact,
    )
    state = RuntimeState(
        client_id="astrbot",
        mode="work",
        relationship_profile="sibling",
        outfit_code="01",
        local_time="2026-07-25T12:00:00+08:00",
        is_holiday=False,
        voice_enabled=False,
        screen_context_enabled=False,
        allowed_expression_tags=["neutral"],
    )

    result = await rag.search("兄さん、今日はどうする？", state, limit=2)

    assert 1 <= len(result) <= 2
    assert all(value.strip() for value in result)


@pytest.mark.asyncio
async def test_irrelevant_vectors_do_not_force_a_lore_result(tmp_path: Path) -> None:
    source_root = Path("datasets/meguri")
    artifact = tmp_path / "dashscope_vectors.json"
    provider = _Embedding()
    await build_dashscope_rag_vectors(
        source_root,
        embedding_provider=provider,
        output_path=artifact,
        batch_size=10,
    )

    class OppositeEmbedding(_Embedding):
        async def embed(self, texts):
            return [[-1.0, 0.0, 0.0] for _ in texts]

    rag = DashScopeRagProvider(
        source_root,
        embedding_provider=OppositeEmbedding(),
        rerank_provider=_Reranker(),  # type: ignore[arg-type]
        artifact_path=artifact,
    )
    state = RuntimeState(
        client_id="astrbot",
        mode="work",
        relationship_profile="sibling",
        outfit_code="01",
        local_time="2026-07-25T12:00:00+08:00",
        is_holiday=False,
        voice_enabled=False,
        screen_context_enabled=False,
        allowed_expression_tags=["neutral"],
    )

    assert await rag.search("zxqv completely unrelated token", state, limit=2) == []
