from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

from fastapi import FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse, StreamingResponse

from .config import BUILD_ID
from .runtime import TurnOrchestrator
from .schemas import ChatResponse, RuntimeOverride, TurnCreateResponse, TurnRequest, TurnStatusResponse


app = FastAPI(title="Meguri Core", version="0.1.0")
orchestrator = TurnOrchestrator()


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "meguri-core", "build_id": BUILD_ID, "mode": "local-mock", "rag_chunks": len(orchestrator.rag.rows)}


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
def list_memories(user_id: str) -> dict:
    return {"user_id": user_id, "items": orchestrator.memory.records.get(user_id, [])}


@app.exception_handler(ValueError)
async def value_error_handler(_, exc: ValueError):
    return JSONResponse(status_code=400, content={"error": str(exc), "build_id": BUILD_ID})
