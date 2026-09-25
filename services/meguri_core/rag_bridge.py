from __future__ import annotations

"""Internal-only bridge from the Java runtime to the configured Lore RAG."""

import inspect
from typing import Any

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from .memory_bridge import _authorize
from .schemas import RuntimeState


router = APIRouter(prefix="/internal/rag", tags=["internal-rag-bridge"])


class BridgeRagSearchRequest(BaseModel):
    query: str = Field(min_length=1)
    runtime_state: RuntimeState
    limit: int = Field(default=3, ge=1, le=10)


@router.post("/search")
async def bridge_rag_search(
    body: BridgeRagSearchRequest, request: Request
) -> dict[str, Any]:
    """Expose the configured canonical retriever only to the Java runtime."""

    await _authorize(request)
    orchestrator = getattr(request.app.state, "orchestrator", None)
    provider = getattr(orchestrator, "rag", None)
    if provider is None:
        raise HTTPException(status_code=503, detail="RAG provider is unavailable")
    result = provider.search(body.query, body.runtime_state, limit=body.limit)
    items = await result if inspect.isawaitable(result) else result
    return {
        "items": [str(item)[:500] for item in items if str(item).strip()],
        "provider": provider.__class__.__name__,
    }
