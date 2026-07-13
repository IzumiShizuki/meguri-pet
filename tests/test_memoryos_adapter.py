import asyncio
import json
import unittest

import httpx

from adapters.memoryos import (
    ExistingMemoryOSAdapter,
    MemoryOSCompatibilityError,
    MemoryOSUnsupportedOperation,
)
from services.meguri_core.memory import (
    MemoryExtractionInput,
    MemorySearchInput,
    MemoryUpsertInput,
    SessionMessage,
    SessionSummaryInput,
)


def success(data, status=200):
    return httpx.Response(status, json={"success": True, "data": data})


class MemoryOSFixture:
    def __init__(self):
        self.requests = []

    async def __call__(self, request):
        body = json.loads(request.content) if request.content else None
        self.requests.append((request.method, request.url.path, body, dict(request.headers)))
        if request.url.path == "/health":
            return success({"service": "memoryos-http", "status": "ok", "cached_scopes": 0})
        if request.url.path.endswith("/retrieve"):
            return success({
                "scope_id": request.url.path.split("/")[-2],
                "episodic": [{
                    "user_input": "User prefers tea",
                    "assistant_response": "Remember unsweetened tea",
                    "timestamp": "2026-07-13T00:00:00+00:00",
                }],
                "summary": {
                    "retrieved_user_knowledge": [{
                        "knowledge": "User avoids overly sweet drinks",
                        "timestamp": "2026-07-12T00:00:00+00:00",
                    }],
                },
            })
        if request.url.path.endswith("/records"):
            return success({
                "scope_id": request.url.path.split("/")[-2],
                "timestamp": body["timestamp"],
                "journal_entry": body,
            }, status=201)
        if request.url.path.endswith("/journal"):
            return success({
                "entries": [{
                    "timestamp": "2026-07-13T00:00:00+00:00",
                    "assistant_response": "Meguri framework uses FastAPI",
                    "meta_data": {
                        "source_client": "website",
                        "source_session": "session-web",
                        "confidence": 0.9,
                        "importance": 4,
                        "memory_type": "ongoing_project",
                    },
                }],
            })
        return httpx.Response(404, json={"success": False, "message": "not found"})


def adapter(fixture, **overrides):
    values = {
        "scope_salt": "test-scope-salt",
        "transport": httpx.MockTransport(fixture),
    }
    values.update(overrides)
    return ExistingMemoryOSAdapter(**values)


class ExistingMemoryOSAdapterTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.fixture = MemoryOSFixture()
        self.adapter = adapter(self.fixture)

    async def test_health_and_authentication_mode(self):
        health = await self.adapter.health()
        self.assertEqual(health["status"], "ok")
        self.assertEqual(self.adapter.authentication_mode, "none")
        self.assertNotIn("authorization", self.fixture.requests[0][3])

    async def test_scope_is_stable_and_user_isolated(self):
        first = self.adapter.scope_id_for_user("user-a")
        again = self.adapter.scope_id_for_user("user-a")
        other = self.adapter.scope_id_for_user("user-b")
        self.assertEqual(first, again)
        self.assertNotEqual(first, other)
        self.assertNotIn("user-a", first)

    async def test_retrieve_maps_episodic_and_profile_hits(self):
        hits = await self.adapter.search(
            MemorySearchInput(user_id="user-a", query="tea", limit=5)
        )
        self.assertEqual(len(hits), 2)
        self.assertEqual(hits[0].record.user_id, "user-a")
        self.assertEqual(hits[0].record.memory_type, "episodic")
        self.assertIn("unsweetened tea", hits[0].record.canonical_text)
        method, path, body, _ = self.fixture.requests[0]
        self.assertEqual(method, "POST")
        self.assertTrue(path.endswith("/retrieve"))
        self.assertEqual(body["journal_limit"], 0)

    async def test_upsert_uses_record_append_with_structured_metadata(self):
        record = await self.adapter.upsert(
            MemoryUpsertInput(
                user_id="user-a",
                memory_type="ongoing_project",
                canonical_text="Meguri framework uses FastAPI",
                source_client="website",
                source_session="session-web",
                confidence=0.9,
                importance=4,
            )
        )
        self.assertTrue(record.memory_id.startswith("memoryos-"))
        _, path, body, _ = self.fixture.requests[0]
        self.assertTrue(path.endswith("/records"))
        self.assertEqual(body["assistant_response"], "Meguri framework uses FastAPI")
        self.assertEqual(body["meta_data"]["memory_type"], "ongoing_project")

    async def test_journal_maps_to_exportable_records(self):
        records = await self.adapter.list_records("user-a")
        self.assertEqual(records[0].memory_type, "ongoing_project")
        self.assertEqual(records[0].source_client, "website")

    async def test_supersede_and_delete_are_explicitly_unsupported(self):
        next_memory = MemoryUpsertInput(
            user_id="user-a",
            memory_type="preference",
            canonical_text="new preference",
            source_client="website",
            source_session="session-web",
            confidence=0.9,
        )
        with self.assertRaises(MemoryOSUnsupportedOperation):
            await self.adapter.supersede("old", next_memory)
        with self.assertRaises(MemoryOSUnsupportedOperation):
            await self.adapter.delete("old")
        self.assertEqual(self.fixture.requests, [])

    async def test_candidate_and_summary_paths_never_call_internal_respond(self):
        candidates = await self.adapter.extract_candidates(
            MemoryExtractionInput(
                user_id="user-a",
                content="I like tea",
                source_client="website",
                source_session="session-web",
            )
        )
        summary = await self.adapter.summarize_session(
            SessionSummaryInput(
                user_id="user-a",
                client_id="website",
                session_id="session-web",
                messages=[SessionMessage(role="user", content="I like tea")],
            )
        )
        self.assertEqual(candidates, [])
        self.assertIn("I like tea", summary.summary)
        self.assertFalse(any(path.endswith("/respond") for _, path, _, _ in self.fixture.requests))

    async def test_concurrent_retrieval_keeps_scope_isolation(self):
        await asyncio.gather(*[
            self.adapter.search(MemorySearchInput(user_id=f"user-{index}", query="tea"))
            for index in range(10)
        ])
        paths = [path for _, path, _, _ in self.fixture.requests]
        self.assertEqual(len(set(paths)), 10)

    async def test_optional_bearer_header_is_supported_for_future_hardening(self):
        secured = adapter(self.fixture, auth_token="test-token")
        await secured.health()
        self.assertEqual(secured.authentication_mode, "bearer")
        self.assertEqual(self.fixture.requests[0][3]["authorization"], "Bearer test-token")

    async def test_public_and_wildcard_urls_require_explicit_override(self):
        with self.assertRaises(ValueError):
            ExistingMemoryOSAdapter("http://0.0.0.0:8788", scope_salt="salt")
        with self.assertRaises(ValueError):
            ExistingMemoryOSAdapter("http://111.228.35.186:8788", scope_salt="salt")

    async def test_failure_envelope_is_not_silently_accepted(self):
        async def failing(_request):
            return httpx.Response(200, json={"success": False, "message": "failed"})

        client = adapter(failing)
        with self.assertRaises(MemoryOSCompatibilityError):
            await client.health()
