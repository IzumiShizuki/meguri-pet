import os
from types import SimpleNamespace
import unittest
from uuid import uuid4

from fastapi.testclient import TestClient

from services.meguri_core.app import app, orchestrator
from services.meguri_core.memory import FakeMemoryProvider


class CandidateOnlyMemoryProvider(FakeMemoryProvider):
    def __init__(self) -> None:
        super().__init__()
        self.submitted = []
        self.upsert_calls = 0

    async def submit_runtime_candidate(self, candidate, **kwargs):
        self.submitted.append((candidate, kwargs))
        return SimpleNamespace(
            candidate_id=uuid4(),
            status=SimpleNamespace(value="pending_review"),
        )

    async def upsert(self, _input):
        self.upsert_calls += 1
        raise AssertionError("bridge must not use legacy upsert")


class MemoryBridgeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.previous = os.environ.get("MEGURI_INTERNAL_BRIDGE_TOKEN")
        self.previous_testclient = os.environ.get("MEGURI_INTERNAL_BRIDGE_ALLOW_TESTCLIENT")
        os.environ["MEGURI_INTERNAL_BRIDGE_TOKEN"] = "bridge-test-token"
        os.environ["MEGURI_INTERNAL_BRIDGE_ALLOW_TESTCLIENT"] = "true"
        orchestrator.memory = FakeMemoryProvider()
        orchestrator.reset()
        self.client = TestClient(app)

    def tearDown(self) -> None:
        if self.previous is None:
            os.environ.pop("MEGURI_INTERNAL_BRIDGE_TOKEN", None)
        else:
            os.environ["MEGURI_INTERNAL_BRIDGE_TOKEN"] = self.previous
        if self.previous_testclient is None:
            os.environ.pop("MEGURI_INTERNAL_BRIDGE_ALLOW_TESTCLIENT", None)
        else:
            os.environ["MEGURI_INTERNAL_BRIDGE_ALLOW_TESTCLIENT"] = self.previous_testclient
        orchestrator.reset()

    def test_bridge_requires_token(self) -> None:
        response = self.client.get("/internal/memory/health")
        self.assertEqual(response.status_code, 503)

    def test_bridge_write_fails_closed_without_candidate_workflow(self) -> None:
        headers = {"X-Meguri-Internal-Token": "bridge-test-token"}
        write = self.client.post(
            "/internal/memory/write",
            headers=headers,
            json={
                "user_id": "bridge-user",
                "source_client": "website",
                "source_session": "bridge-session",
                "source_turn_id": "turn-1",
                "trace_id": "trace-1",
                "candidates": [
                    {
                        "type": "preference",
                        "summary": "我喜欢喝茶",
                        "confidence": 0.8,
                    }
                ],
            },
        )
        self.assertEqual(write.status_code, 503)
        self.assertEqual(orchestrator.memory.records, {})

    def test_bridge_submits_candidate_without_legacy_upsert(self) -> None:
        provider = CandidateOnlyMemoryProvider()
        orchestrator.memory = provider
        response = self.client.post(
            "/internal/memory/write",
            headers={"X-Meguri-Internal-Token": "bridge-test-token"},
            json={
                "user_id": "bridge-user",
                "source_client": "website",
                "source_session": "bridge-session",
                "source_turn_id": "turn-1",
                "trace_id": "trace-1",
                "candidates": [
                    {
                        "type": "preference",
                        "summary": "User prefers tea",
                        "confidence": 0.8,
                    }
                ],
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "pending")
        self.assertEqual(response.json()["written_ids"], [])
        self.assertEqual(len(response.json()["candidate_ids"]), 1)
        self.assertEqual(len(provider.submitted), 1)
        self.assertEqual(provider.upsert_calls, 0)
        self.assertEqual(provider.records, {})

    def test_bridge_does_not_echo_rejected_credential_content(self) -> None:
        provider = CandidateOnlyMemoryProvider()
        orchestrator.memory = provider
        secret = "My API key is sk-do-not-echo"
        response = self.client.post(
            "/internal/memory/write",
            headers={"X-Meguri-Internal-Token": "bridge-test-token"},
            json={
                "user_id": "bridge-user",
                "source_client": "website",
                "source_session": "bridge-session",
                "source_turn_id": "turn-credential",
                "trace_id": "trace-credential",
                "candidates": [
                    {
                        "type": "identity",
                        "summary": secret,
                        "confidence": 1.0,
                    }
                ],
            },
        )

        self.assertEqual(response.status_code, 200)
        event_candidate = response.json()["events"][0]["candidate"]
        self.assertEqual(
            event_candidate["summary"], "[redacted unsafe memory candidate]"
        )
        self.assertEqual(len(event_candidate["content_sha256"]), 64)
        self.assertNotIn(secret, response.text)
        self.assertEqual(provider.upsert_calls, 0)

    def test_bridge_persists_a_bounded_session_summary(self) -> None:
        headers = {"X-Meguri-Internal-Token": "bridge-test-token"}
        response = self.client.post(
            "/internal/memory/session-summary",
            headers=headers,
            json={
                "user_id": "bridge-user",
                "client_id": "desktop_pet",
                "session_id": "sleep-session",
                "messages": [
                    {"role": "user", "content": "我喜欢喝茶"},
                    {"role": "assistant", "content": "我记住了"},
                ],
                "structured_candidates": [
                    {"type": "preference", "summary": "喜欢喝茶", "confidence": 0.8}
                ],
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["message_count"], 2)
        self.assertIn("我喜欢喝茶", response.json()["summary"])
        self.assertEqual(len(response.json()["structured_candidates"]), 1)
        self.assertEqual(response.json()["candidate_status"], "audit_only")
        self.assertEqual(response.json()["candidate_ids"], [])

    def test_rag_bridge_uses_the_configured_provider(self) -> None:
        class FakeRag:
            async def search(self, query, state, limit=3):
                self.received = (query, state.relationship_profile, limit)
                return ["canonical Japanese line"]

        previous_rag = orchestrator.rag
        orchestrator.rag = FakeRag()
        try:
            response = self.client.post(
                "/internal/rag/search",
                headers={"X-Meguri-Internal-Token": "bridge-test-token"},
                json={
                    "query": "hello",
                    "limit": 2,
                    "runtime_state": {
                        "client_id": "astrbot",
                        "mode": "work",
                        "relationship_profile": "sibling",
                        "outfit_code": "01",
                        "local_time": "2026-07-25T12:00:00+08:00",
                        "is_holiday": False,
                        "voice_enabled": False,
                        "screen_context_enabled": False,
                        "allowed_expression_tags": ["neutral"],
                    },
                },
            )
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["items"], ["canonical Japanese line"])
            self.assertEqual(orchestrator.rag.received, ("hello", "sibling", 2))
        finally:
            orchestrator.rag = previous_rag
