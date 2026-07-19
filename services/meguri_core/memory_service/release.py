from __future__ import annotations

from typing import Any


DATABASE_REVISION = "20260714_0004"
EMBEDDING_MODEL = "BAAI/bge-m3"
EMBEDDING_MODEL_REVISION = "5617a9f61b028005a4858fdac845db406aefb181"
EMBEDDING_DIMENSION = 1024


def memory_release_metadata() -> dict[str, Any]:
    return {
        "database_revision": DATABASE_REVISION,
        "embedding_model": EMBEDDING_MODEL,
        "embedding_model_revision": EMBEDDING_MODEL_REVISION,
        "embedding_dimension": EMBEDDING_DIMENSION,
        "vector_search_mode": "exact",
        "ann_index_enabled": False,
    }
