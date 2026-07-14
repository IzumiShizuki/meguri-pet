from __future__ import annotations

import asyncio
import json
import threading
import unittest

from fastapi.testclient import TestClient

from services.meguri_core.schemas import LlmResponse
from training.llm.gateway.app import GatewaySettings, create_app
from training.llm.scripts.common import PipelineError


METADATA = {
    "model_id": "candidate-one",
    "base_revision": "a" * 40,
    "adapter_revision": "b" * 16,
    "adapter_sha256": "b" * 64,
    "prompt_sha256": "c" * 64,
    "response_schema_sha256": "d" * 64,
}


class FakeManager:
    def __init__(self, *, fail: bool = False, delay: float = 0.0) -> None:
        self.fail = fail
        self.delay = delay
        self.cancel_event = None

    def readiness(self):
        return {"ready": True, "active_model_id": "candidate-one", "issues": []}

    async def generate(self, requested_model, messages, cancel_event):
        self.cancel_event = cancel_event
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.fail:
            raise PipelineError("schema-invalid model output")
        return (
            LlmResponse(
                reply="了解了",
                expression_tag="neutral",
                expression_intensity="low",
                voice_style="neutral",
                memory_candidates=[],
            ),
            METADATA,
        )


def payload(stream: bool = False):
    return {
        "model": "candidate-one",
        "messages": [
            {"role": "system", "content": "system"},
            {"role": "user", "content": "user"},
        ],
        "temperature": 0,
        "stream": stream,
    }


class GatewayTests(unittest.TestCase):
    def client(self, manager=None, timeout=1.0):
        app = create_app(
            manager or FakeManager(),
            GatewaySettings(api_key="secret", timeout_seconds=timeout, max_concurrency=1),
        )
        return TestClient(app)

    def test_auth_and_nonstream_contract(self) -> None:
        client = self.client()
        self.assertEqual(client.post("/v1/chat/completions", json=payload()).status_code, 401)
        response = client.post(
            "/v1/chat/completions",
            json=payload(),
            headers={"Authorization": "Bearer secret"},
        )
        self.assertEqual(response.status_code, 200)
        content = response.json()["choices"][0]["message"]["content"]
        self.assertEqual(json.loads(content)["reply"], "了解了")
        self.assertEqual(response.headers["X-Meguri-Adapter-Revision"], "b" * 16)

    def test_schema_failure_is_fail_closed(self) -> None:
        response = self.client(FakeManager(fail=True)).post(
            "/v1/chat/completions",
            json=payload(),
            headers={"Authorization": "Bearer secret"},
        )
        self.assertEqual(response.status_code, 502)
        self.assertNotIn("choices", response.json())

    def test_sse_is_emitted_only_after_validated_response(self) -> None:
        response = self.client().post(
            "/v1/chat/completions",
            json=payload(stream=True),
            headers={"Authorization": "Bearer secret"},
        )
        self.assertEqual(response.status_code, 200)
        self.assertIn("data: [DONE]", response.text)
        self.assertIn("chat.completion.chunk", response.text)

    def test_timeout_sets_generation_cancellation(self) -> None:
        manager = FakeManager(delay=0.2)
        response = self.client(manager, timeout=0.02).post(
            "/v1/chat/completions",
            json=payload(),
            headers={"Authorization": "Bearer secret"},
        )
        self.assertEqual(response.status_code, 504)
        self.assertIsNotNone(manager.cancel_event)
        self.assertTrue(manager.cancel_event.is_set())

if __name__ == "__main__":
    unittest.main()
