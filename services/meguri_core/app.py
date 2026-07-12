from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse

from .config import BUILD_ID
from .runtime import TurnOrchestrator
from .schemas import ChatResponse, RuntimeOverride, TurnCreateResponse, TurnRequest


app = FastAPI(title="Meguri Core", version="0.1.0")
orchestrator = TurnOrchestrator()


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "meguri-core", "build_id": BUILD_ID, "mode": "local-mock", "rag_chunks": len(orchestrator.rag.rows)}


@app.post("/v1/chat/respond", response_model=ChatResponse)
def chat_respond(request: TurnRequest) -> ChatResponse:
    return orchestrator.run(request)


@app.post("/v1/turns", response_model=TurnCreateResponse)
def create_turn(request: TurnRequest) -> TurnCreateResponse:
    result = orchestrator.run(request)
    return TurnCreateResponse(turn_id=result.turn_id, session_id=result.session_id, build_id=BUILD_ID, status="completed")


@app.get("/v1/sessions/{session_id}/events")
async def session_events(session_id: str) -> StreamingResponse:
    events = orchestrator.events.get(session_id, [])
    async def stream() -> AsyncIterator[str]:
        for event in events:
            yield f"id: {event.sequence}\nevent: {event.type}\ndata: {event.model_dump_json()}\n\n"
            await asyncio.sleep(0)
    return StreamingResponse(stream(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Meguri-Build": BUILD_ID})


@app.post("/v1/turns/{turn_id}/cancel")
def cancel_turn(turn_id: str) -> dict:
    if not orchestrator.cancel(turn_id):
        raise HTTPException(status_code=404, detail="turn not found")
    return {"turn_id": turn_id, "status": "cancel_requested"}


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
