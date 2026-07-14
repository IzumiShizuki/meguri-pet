from __future__ import annotations

import asyncio
import os
from collections.abc import AsyncIterator
from uuid import UUID

from fastapi import FastAPI, Header, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.responses import PlainTextResponse

from .config import BUILD_ID
from .memory import ManualMemoryReviewRequest
from .runtime import TurnOrchestrator
from .schemas import ChatResponse, RuntimeOverride, TurnCreateResponse, TurnRequest, TurnStatusResponse


app = FastAPI(title="Meguri Core", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://127.0.0.1:4173",
        "http://127.0.0.1:5173",
        "http://localhost:4173",
        "http://localhost:5173",
    ],
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=[
        "Content-Type",
        "Idempotency-Key",
        "Last-Event-ID",
        "X-Request-ID",
    ],
)
orchestrator = TurnOrchestrator()
app.state.orchestrator = orchestrator


@app.get("/health")
def health() -> dict:
    provider_name = getattr(orchestrator.llm, "provider_name", "unknown")
    return {
        "status": "ok",
        "service": "meguri-core",
        "build_id": BUILD_ID,
        "mode": "local-mock" if provider_name == "mock" else "configured-provider",
        "llm_provider": provider_name,
        "memory_provider": getattr(orchestrator.memory, "provider_name", "fake"),
        "rag_chunks": len(orchestrator.rag.rows),
    }


@app.post("/v1/chat/respond", response_model=ChatResponse)
async def chat_respond(request: TurnRequest) -> ChatResponse:
    return await orchestrator.run_inline(request)


@app.post("/v1/turns", response_model=TurnCreateResponse, status_code=202)
async def create_turn(request: TurnRequest, idempotency_key: str | None = Header(default=None, alias="Idempotency-Key")) -> TurnCreateResponse:
    record = await orchestrator.start(request, idempotency_key=idempotency_key)
    return TurnCreateResponse(turn_id=record.turn_id, session_id=request.session_id, build_id=BUILD_ID, status=record.status)


@app.get("/v1/turns/{turn_id}", response_model=TurnStatusResponse)
def get_turn(turn_id: str) -> TurnStatusResponse:
    record = orchestrator.turns.get(turn_id)
    if record is None:
        raise HTTPException(status_code=404, detail="turn not found")
    return TurnStatusResponse(turn_id=record.turn_id, session_id=record.request.session_id, status=record.status, build_id=BUILD_ID, error=record.error)


@app.get("/v1/sessions/{session_id}/events")
async def session_events(
    session_id: str,
    request: Request,
    after_sequence: int = Query(default=0, ge=0),
    last_event_id: str | None = Header(default=None, alias="Last-Event-ID"),
) -> StreamingResponse:
    if last_event_id and last_event_id.isdigit():
        after_sequence = max(after_sequence, int(last_event_id))

    async def stream() -> AsyncIterator[str]:
        cursor = after_sequence
        heartbeat_seconds = 1.0
        while True:
            if await request.is_disconnected():
                return
            available = [event for event in orchestrator.events.get(session_id, []) if event.sequence > cursor]
            for event in available:
                cursor = event.sequence
                yield f"id: {event.sequence}\nevent: {event.type}\ndata: {event.model_dump_json()}\n\n"
            if not orchestrator.session_is_active(session_id):
                return
            condition = orchestrator.conditions.setdefault(session_id, asyncio.Condition())
            try:
                async with condition:
                    await asyncio.wait_for(condition.wait(), timeout=heartbeat_seconds)
            except TimeoutError:
                yield ": heartbeat\n\n"

    return StreamingResponse(stream(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no", "X-Meguri-Build": BUILD_ID})


@app.post("/v1/turns/{turn_id}/cancel")
def cancel_turn(turn_id: str) -> dict:
    record = orchestrator.cancel(turn_id)
    if record is None:
        raise HTTPException(status_code=404, detail="turn not found")
    status = record.status if record.status in orchestrator.terminal_statuses else "cancel_requested"
    return {"turn_id": turn_id, "status": status}


@app.get("/v1/runtime/state")
def runtime_state(user_id: str, client_id: str = "website", session_id: str = "state") -> dict:
    request = TurnRequest(user_id=user_id, client_id=client_id, session_id=session_id, message="state")
    return orchestrator.state_machine.state_for(request).model_dump(mode="json")


@app.post("/v1/runtime/override")
def set_runtime_override(user_id: str, override: RuntimeOverride) -> dict:
    orchestrator.state_machine.set_override(user_id, override)
    return {"user_id": user_id, "override": override.model_dump(mode="json")}


@app.delete("/v1/runtime/override/{scope}")
def delete_runtime_override(scope: str) -> dict:
    orchestrator.state_machine.clear_override(scope)
    return {"scope": scope, "status": "cleared"}


@app.get("/v1/memories")
async def list_memories(user_id: str, include_deleted: bool = False) -> dict:
    records = await orchestrator.memory.list_records(user_id, include_deleted=include_deleted)
    return {"user_id": user_id, "items": [record.model_dump(mode="json") for record in records]}


@app.get("/v1/memories/export")
async def export_memories(user_id: str) -> dict:
    records = await orchestrator.memory.list_records(user_id, include_deleted=True)
    return {
        "user_id": user_id,
        "build_id": BUILD_ID,
        "items": [record.model_dump(mode="json") for record in records],
    }


@app.post("/v1/memories/review")
async def review_memory(request: ManualMemoryReviewRequest) -> dict:
    existing = await orchestrator.memory.list_records(request.user_id)
    decision = orchestrator.memory_policy.manual_review(request, existing)
    record = None
    if decision.status == "accepted" and decision.upsert is not None:
        record = await orchestrator.memory.upsert(decision.upsert)
    return {
        "status": decision.status,
        "reason": decision.reason,
        "record": record.model_dump(mode="json") if record else None,
    }


@app.delete("/v1/memories/{memory_id}")
async def delete_memory(
    memory_id: str,
    request: Request,
    user_id: str | None = None,
    reason: str = "user_requested",
    request_id: str | None = Header(default=None, alias="X-Request-ID"),
) -> dict:
    from .api_auth import get_api_principal
    from .memory_api import memory_call
    from .memory_service.contracts import AuthoritativeMemoryProvider

    if isinstance(orchestrator.memory, AuthoritativeMemoryProvider):
        principal = await get_api_principal(request)
        if not principal.formal_memory_allowed:
            raise HTTPException(
                status_code=403,
                detail={"code": "verified_binding_required", "message": "verified identity binding is required"},
            )
        if request_id is None or not request_id.strip():
            raise HTTPException(
                status_code=400,
                detail={"code": "request_id_required", "message": "X-Request-ID is required"},
            )
        await memory_call(
            orchestrator.memory.delete(
                UUID(memory_id),
                tenant_id=principal.tenant_id,
                user_id=principal.user_id,
                reason=reason,
                actor=principal.memory_actor(),
                request_id=request_id,
            )
        )
        return {"memory_id": memory_id, "status": "deleted"}
    if os.getenv("MEGURI_ENV") == "production":
        raise HTTPException(status_code=403, detail="legacy memory mutation is disabled")
    if user_id is None:
        raise HTTPException(status_code=400, detail="user_id is required")
    records = await orchestrator.memory.list_records(user_id, include_deleted=True)
    if not any(record.memory_id == memory_id for record in records):
        raise HTTPException(status_code=404, detail="memory not found")
    try:
        await orchestrator.memory.delete(memory_id)
    except KeyError:
        raise HTTPException(status_code=404, detail="memory not found") from None
    return {"memory_id": memory_id, "status": "deleted"}


@app.exception_handler(ValueError)
async def value_error_handler(_, exc: ValueError):
    return JSONResponse(status_code=400, content={"error": str(exc), "build_id": BUILD_ID})


from .identity_api import router as identity_router
from .memory_api import router as authoritative_memory_router
from .memory_service.metrics import memory_metrics

app.include_router(authoritative_memory_router)
app.include_router(identity_router)


@app.get("/metrics", response_class=PlainTextResponse)
def metrics() -> str:
    return memory_metrics.render()
