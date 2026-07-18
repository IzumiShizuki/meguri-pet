from __future__ import annotations

import asyncio
import hmac
import json
import os
import threading
import time
from contextlib import suppress
from dataclasses import dataclass
from typing import Any, Protocol
from uuid import uuid4

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse

from services.meguri_core.schemas import LlmResponse
from services.meguri_core.secrets import SecretConfigurationError, read_secret
from training.llm.gateway.schemas import ChatCompletionRequest
from training.llm.scripts.common import PipelineError, canonical_json


class ModelManager(Protocol):
    def readiness(self) -> dict[str, Any]: ...

    async def generate(
        self,
        requested_model: str,
        messages: list[dict[str, str]],
        cancel_event: threading.Event,
    ) -> tuple[LlmResponse, dict[str, str]]: ...


@dataclass(frozen=True)
class GatewaySettings:
    api_key: str
    timeout_seconds: float = 60.0
    max_concurrency: int = 1
    acquire_timeout_seconds: float = 0.1

    def validate(self) -> None:
        if not self.api_key:
            raise ValueError("gateway API key must not be empty")
        if self.timeout_seconds <= 0 or self.max_concurrency <= 0 or self.acquire_timeout_seconds <= 0:
            raise ValueError("gateway timeout and concurrency settings must be positive")


def _headers(metadata: dict[str, str]) -> dict[str, str]:
    headers = {
        "X-Meguri-Model-Id": metadata["model_id"],
        "X-Meguri-Base-Revision": metadata["base_revision"],
        "X-Meguri-Adapter-Revision": metadata["adapter_revision"],
        "X-Meguri-Adapter-SHA256": metadata["adapter_sha256"],
    }
    if metadata.get("generation_profile_id"):
        headers["X-Meguri-Generation-Profile-Id"] = metadata["generation_profile_id"]
        headers["X-Meguri-Generation-Profile-SHA256"] = metadata[
            "generation_profile_sha256"
        ]
    return headers


def create_app(manager: ModelManager, settings: GatewaySettings) -> FastAPI:
    settings.validate()
    app = FastAPI(title="Meguri authenticated LLM gateway", version="1.0.0")
    semaphore = asyncio.Semaphore(settings.max_concurrency)

    def authorize(value: str | None) -> None:
        expected = f"Bearer {settings.api_key}"
        if value is None or not hmac.compare_digest(value, expected):
            raise HTTPException(status_code=401, detail="invalid gateway credentials")

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/ready")
    async def ready(authorization: str | None = Header(default=None)) -> JSONResponse:
        authorize(authorization)
        value = manager.readiness()
        return JSONResponse(status_code=200 if value.get("ready") else 503, content=value)

    @app.post("/v1/chat/completions")
    async def completions(
        payload: ChatCompletionRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> Any:
        authorize(authorization)
        if payload.temperature != 0:
            raise HTTPException(status_code=400, detail="staging gateway requires deterministic temperature=0")
        try:
            await asyncio.wait_for(semaphore.acquire(), timeout=settings.acquire_timeout_seconds)
        except TimeoutError as exc:
            raise HTTPException(status_code=429, detail="gateway concurrency limit reached") from exc
        cancel_event = threading.Event()

        async def monitor_disconnect() -> None:
            while not cancel_event.is_set():
                if await request.is_disconnected():
                    cancel_event.set()
                    return
                await asyncio.sleep(0.05)

        monitor = asyncio.create_task(monitor_disconnect())
        try:
            try:
                response, metadata = await asyncio.wait_for(
                    manager.generate(
                        payload.model,
                        [item.model_dump() for item in payload.messages],
                        cancel_event,
                    ),
                    timeout=settings.timeout_seconds,
                )
            except TimeoutError as exc:
                cancel_event.set()
                raise HTTPException(status_code=504, detail="model generation timed out") from exc
            except PipelineError as exc:
                cancel_event.set()
                raise HTTPException(status_code=502, detail=str(exc)) from exc
        finally:
            cancel_event.set()
            monitor.cancel()
            with suppress(asyncio.CancelledError):
                await monitor
            semaphore.release()
        content = canonical_json(response.model_dump(mode="json"))
        completion_id = "chatcmpl-" + uuid4().hex
        created = int(time.time())
        if not payload.stream:
            return JSONResponse(
                headers=_headers(metadata),
                content={
                    "id": completion_id,
                    "object": "chat.completion",
                    "created": created,
                    "model": metadata["model_id"],
                    "choices": [{"index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": "stop"}],
                    "meguri_model": metadata,
                },
            )

        async def event_stream():
            for index in range(0, len(content), 48):
                if await request.is_disconnected():
                    return
                chunk = {
                    "id": completion_id,
                    "object": "chat.completion.chunk",
                    "created": created,
                    "model": metadata["model_id"],
                    "choices": [{"index": 0, "delta": {"content": content[index : index + 48]}, "finish_reason": None}],
                }
                yield "data: " + json.dumps(chunk, ensure_ascii=False) + "\n\n"
            yield "data: [DONE]\n\n"

        return StreamingResponse(event_stream(), media_type="text/event-stream", headers=_headers(metadata))

    return app


def settings_from_env() -> GatewaySettings:
    try:
        api_key = read_secret(os.environ, "MEGURI_LLM_API_KEY")
    except SecretConfigurationError as exc:
        raise RuntimeError(str(exc)) from exc
    return GatewaySettings(
        api_key=str(api_key),
        timeout_seconds=float(os.environ.get("MEGURI_LLM_TIMEOUT_SECONDS", "60")),
        max_concurrency=int(os.environ.get("MEGURI_LLM_MAX_CONCURRENCY", "1")),
    )
