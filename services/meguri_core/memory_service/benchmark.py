from __future__ import annotations

import math
import random
from time import perf_counter

from pydantic import Field

from .models import StrictModel


class SearchBenchmarkResult(StrictModel):
    engine: str
    status: str
    corpus_size: int = Field(ge=1)
    query_count: int = Field(ge=1)
    dimension: int = Field(ge=1)
    top_k: int = Field(ge=1)
    p50_ms: float | None = Field(default=None, ge=0)
    p95_ms: float | None = Field(default=None, ge=0)
    p99_ms: float | None = Field(default=None, ge=0)
    error_rate: float | None = Field(default=None, ge=0, le=1)
    recall_at_k: float | None = Field(default=None, ge=0, le=1)
    notes: str


class ExactAnnBenchmark(StrictModel):
    seed: int
    exact: SearchBenchmarkResult
    ann: SearchBenchmarkResult


def _percentile(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def _unit_vector(randomizer: random.Random, dimension: int) -> tuple[float, ...]:
    values = [randomizer.uniform(-1, 1) for _ in range(dimension)]
    norm = math.sqrt(sum(value * value for value in values)) or 1
    return tuple(value / norm for value in values)


def run_synthetic_exact_ann_benchmark(
    *,
    corpus_size: int = 500,
    query_count: int = 40,
    dimension: int = 1024,
    top_k: int = 5,
    seed: int = 20260714,
) -> ExactAnnBenchmark:
    if corpus_size < 1 or query_count < 1 or dimension < 1 or top_k < 1:
        raise ValueError("benchmark sizes must be positive")
    randomizer = random.Random(seed)
    corpus = [_unit_vector(randomizer, dimension) for _ in range(corpus_size)]
    latencies: list[float] = []
    errors = recalls = 0
    for _ in range(query_count):
        expected = randomizer.randrange(corpus_size)
        query = corpus[expected]
        started = perf_counter()
        try:
            scores = [
                (sum(left * right for left, right in zip(query, vector)), index)
                for index, vector in enumerate(corpus)
            ]
            top = {
                index
                for _, index in sorted(scores, reverse=True)[: min(top_k, corpus_size)]
            }
            recalls += expected in top
        except Exception:
            errors += 1
        latencies.append((perf_counter() - started) * 1000)
    exact = SearchBenchmarkResult(
        engine="in_process_exact_cosine",
        status="measured",
        corpus_size=corpus_size,
        query_count=query_count,
        dimension=dimension,
        top_k=top_k,
        p50_ms=_percentile(latencies, 0.50),
        p95_ms=_percentile(latencies, 0.95),
        p99_ms=_percentile(latencies, 0.99),
        error_rate=errors / query_count,
        recall_at_k=recalls / query_count,
        notes="Synthetic in-process exact cosine baseline; not PostgreSQL network latency.",
    )
    ann = SearchBenchmarkResult(
        engine="pgvector_hnsw",
        status="not_enabled",
        corpus_size=corpus_size,
        query_count=query_count,
        dimension=dimension,
        top_k=top_k,
        notes="No HNSW migration exists; ANN must remain disabled until live comparison evidence is approved.",
    )
    return ExactAnnBenchmark(seed=seed, exact=exact, ann=ann)
