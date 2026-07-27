from __future__ import annotations

from typing import Any


DATABASE_REVISION = "20260728_0006"
# The managed RAG release uses Alibaba Cloud Model Studio's multilingual
# embedding endpoint.  The revision is a Meguri release fingerprint: Model
# Studio does not expose an immutable model-commit identifier in its response,
# so changing the provider model, dimensions, or validation date requires a
# new value and a full rebuild of the derived vectors.
EMBEDDING_MODEL = "text-embedding-v4"
EMBEDDING_MODEL_REVISION = "dashscope-text-embedding-v4-2048-r20260725"
EMBEDDING_DIMENSION = 2048


def memory_release_metadata() -> dict[str, Any]:
    return {
        "database_revision": DATABASE_REVISION,
        "embedding_model": EMBEDDING_MODEL,
        "embedding_model_revision": EMBEDDING_MODEL_REVISION,
        "embedding_dimension": EMBEDDING_DIMENSION,
        "vector_search_mode": "exact",
        "ann_index_enabled": False,
    }
