import unittest
from types import SimpleNamespace
from uuid import uuid4

from services.meguri_core.memory import (
    CompanionMemoryPolicy,
    FakeMemoryProvider,
    ManualMemoryReviewRequest,
    MemorySearchInput,
    MemoryUpsertInput,
    SessionContextStore,
    SessionMessage,
    SessionSummaryInput,
)
from services.meguri_core.schemas import MemoryCandidate
from services.meguri_core.schemas import LlmResponse
from services.meguri_core.runtime import TurnOrchestrator
from services.meguri_core.schemas import TurnRequest
from services.meguri_core.memory_service.enums import CandidateStatus


def upsert_input(**overrides):
    values = {
        "user_id": "user-a",
        "memory_type": "preference",
        "canonical_text": "User prefers unsweetened tea",
        "source_client": "website",
        "source_session": "session-web",
        "confidence": 0.9,
        "sensitivity": "normal",
        "importance": 3,
    }
    values.update(overrides)
    return MemoryUpsertInput(**values)


class FakeMemoryProviderTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.provider = FakeMemoryProvider()

    async def test_formal_memories_are_shared_by_user(self):
        record = await self.provider.upsert(upsert_input())
        hits = await self.provider.search(MemorySearchInput(user_id="user-a", query="tea"))
        self.assertEqual(hits[0].record.memory_id, record.memory_id)
        self.assertEqual(hits[0].record.source_client, "website")
        isolated = await self.provider.search(MemorySearchInput(user_id="user-b", query="tea"))
        self.assertEqual(isolated, [])

    async def test_upsert_deduplicates_and_supersede_versions(self):
        first = await self.provider.upsert(upsert_input())
        duplicate = await self.provider.upsert(upsert_input(confidence=0.95))
        self.assertEqual(first.memory_id, duplicate.memory_id)
        replacement = await self.provider.supersede(
            first.memory_id,
            upsert_input(canonical_text="User now prefers black coffee", source_client="desktop_pet"),
        )
        self.assertEqual(self.provider.records[first.memory_id].status, "superseded")
        self.assertEqual(replacement.version, 2)
        self.assertEqual(replacement.supersedes_memory_id, first.memory_id)

    async def test_delete_and_export_visibility(self):
        record = await self.provider.upsert(upsert_input())
        await self.provider.delete(record.memory_id)
        self.assertEqual(await self.provider.list_records("user-a"), [])
        exported = await self.provider.list_records("user-a", include_deleted=True)
        self.assertEqual(exported[0].status, "deleted")

    async def test_session_summary_contract(self):
        summary = await self.provider.summarize_session(
            SessionSummaryInput(
                user_id="user-a",
                client_id="website",
                session_id="session-web",
                messages=[
                    SessionMessage(role="user", content="Continue the project"),
                    SessionMessage(role="assistant", content="I will continue"),
                ],
            )
        )
        self.assertEqual(summary.message_count, 2)
        self.assertIn("Continue the project", summary.summary)


class CompanionMemoryPolicyTests(unittest.TestCase):
    def setUp(self):
        self.policy = CompanionMemoryPolicy()

    def review(self, candidate, existing=None):
        return self.policy.review(
            user_id="user-a",
            source_client="website",
            source_session="session-web",
            candidates=[candidate],
            existing=existing or [],
        )[0]

    def test_accepts_stable_high_confidence_candidate(self):
        decision = self.review(MemoryCandidate(type="project", summary="Meguri runtime uses FastAPI", confidence=0.9))
        self.assertEqual(decision.status, "accepted")
        self.assertEqual(decision.upsert.memory_type, "ongoing_project")
        self.assertEqual(decision.upsert.importance, 4)

    def test_rejects_credentials_and_sensitive_values(self):
        credential = self.review(MemoryCandidate(type="identity", summary="My API key is abc", confidence=1.0))
        sensitive = self.review(
            MemoryCandidate(type="identity", summary="private identity value", confidence=1.0, sensitivity="sensitive")
        )
        self.assertEqual(credential.status, "rejected")
        self.assertEqual(sensitive.status, "rejected")

    def test_private_and_low_confidence_candidates_require_review(self):
        private = self.review(
            MemoryCandidate(type="event", summary="private event", confidence=0.9, sensitivity="private")
        )
        uncertain = self.review(MemoryCandidate(type="event", summary="uncertain event", confidence=0.5))
        self.assertEqual(private.status, "pending_review")
        self.assertEqual(uncertain.status, "pending_review")

    def test_manual_review_can_accept_private_but_not_credentials(self):
        private = self.policy.manual_review(
            ManualMemoryReviewRequest(
                user_id="user-a",
                candidate=MemoryCandidate(
                    type="event",
                    summary="private but useful event",
                    confidence=0.6,
                    sensitivity="private",
                ),
                decision="accept",
                source_client="website",
                source_session="session-web",
            ),
            existing=[],
        )
        credential = self.policy.manual_review(
            ManualMemoryReviewRequest(
                user_id="user-a",
                candidate=MemoryCandidate(
                    type="identity",
                    summary="password is still forbidden",
                    confidence=1.0,
                ),
                decision="accept",
                source_client="website",
                source_session="session-web",
            ),
            existing=[],
        )
        self.assertEqual(private.status, "accepted")
        self.assertEqual(private.upsert.sensitivity, "private")
        self.assertEqual(credential.status, "rejected")


class SessionContextStoreTests(unittest.TestCase):
    def test_context_isolated_by_user_client_and_session(self):
        store = SessionContextStore(max_messages=2)
        store.append("user-a", "website", "session-1", SessionMessage(role="user", content="first"))
        store.append("user-a", "website", "session-1", SessionMessage(role="assistant", content="second"))
        store.append("user-a", "website", "session-1", SessionMessage(role="user", content="third"))
        self.assertEqual([message.content for message in store.recent("user-a", "website", "session-1")], ["second", "third"])
        self.assertEqual(store.recent("user-a", "astrbot", "session-1"), [])
        self.assertEqual(store.recent("user-a", "website", "session-2"), [])
        self.assertEqual(store.recent("user-b", "website", "session-1"), [])


class FailingMemoryProvider:
    async def search(self, _):
        raise ConnectionError("memory unavailable")

    def reset(self):
        pass


class MemoryFailureIsolationTests(unittest.IsolatedAsyncioTestCase):
    async def test_memory_failure_does_not_block_text_turn(self):
        runtime = TurnOrchestrator(
            stream_interval=0,
            memory_provider=FailingMemoryProvider(),
        )
        result = await runtime.run_inline(
            TurnRequest(
                user_id="user-a",
                client_id="website",
                session_id="session-web",
                message="continue without memory",
            )
        )
        self.assertEqual(result.memory_status, "unavailable")
        event_types = [event.type for event in runtime.events["session-web"]]
        self.assertIn("text.completed", event_types)
        self.assertIn("turn.completed", event_types)
        self.assertNotIn("turn.failed", event_types)


class RuntimeCandidateQueueTests(unittest.IsolatedAsyncioTestCase):
    async def test_native_runtime_queues_llm_candidate_without_auto_approval(self):
        class CandidateLlm:
            provider_name = "test"

            async def respond(self, *_args):
                return LlmResponse(
                    reply="I will remember that as a candidate.",
                    memory_candidates=[
                        MemoryCandidate(
                            type="preference",
                            summary="User prefers unsweetened tea",
                            confidence=0.95,
                        )
                    ],
                )

        class AuthoritativeRuntimeMemory:
            def __init__(self):
                self.submitted = []

            async def search(self, _input):
                return []

            async def list_records(self, _user_id):
                return []

            async def extract_candidates(self, _input):
                return []

            async def submit_runtime_candidate(self, candidate, **kwargs):
                self.submitted.append((candidate, kwargs))
                return SimpleNamespace(
                    candidate_id=uuid4(),
                    status=CandidateStatus.PENDING_REVIEW,
                )

            async def upsert(self, _input):
                raise AssertionError("runtime must not auto-approve native candidates")

        memory = AuthoritativeRuntimeMemory()
        runtime = TurnOrchestrator(
            stream_interval=0,
            memory_provider=memory,  # type: ignore[arg-type]
            llm_provider=CandidateLlm(),
        )
        result = await runtime.run_inline(
            TurnRequest(
                user_id="user-a",
                client_id="website",
                session_id="session-a",
                message="remember my preference",
            )
        )

        self.assertEqual(result.memory_status, "pending")
        self.assertEqual(len(memory.submitted), 1)
        self.assertEqual(memory.submitted[0][1]["source_client"], "website")
        completed = [
            event
            for event in runtime.events["session-a"]
            if event.type == "memory.write.completed"
        ][0]
        self.assertEqual(completed.data["written_ids"], [])
        self.assertEqual(len(completed.data["candidate_ids"]), 1)

    async def test_unverified_identity_cannot_read_or_write_formal_memory(self):
        class CandidateLlm:
            provider_name = "test"

            async def respond(self, *_args):
                return LlmResponse(
                    reply="I can answer without formal memory.",
                    memory_candidates=[
                        MemoryCandidate(
                            type="preference",
                            summary="User prefers unsweetened tea",
                            confidence=0.95,
                        )
                    ],
                )

        class FormalMemoryMustRemainUnused:
            async def search(self, _input):
                raise AssertionError("unverified identity must not read formal memory")

            async def extract_candidates(self, _input):
                raise AssertionError("unverified identity must not extract formal memory")

            async def list_records(self, _user_id):
                raise AssertionError("unverified identity must not list formal memory")

            async def submit_runtime_candidate(self, _candidate, **_kwargs):
                raise AssertionError("unverified identity must not write formal memory")

        runtime = TurnOrchestrator(
            stream_interval=0,
            memory_provider=FormalMemoryMustRemainUnused(),  # type: ignore[arg-type]
            llm_provider=CandidateLlm(),
        )
        result = await runtime.run_inline(
            TurnRequest(
                user_id="isolated-user",
                client_id="airi",
                session_id="isolated-session",
                message="remember my preference",
                formal_memory_allowed=False,
            )
        )

        self.assertEqual(result.memory_status, "unavailable")
        completed = [
            event
            for event in runtime.events["isolated-session"]
            if event.type == "memory.write.completed"
        ][0]
        self.assertEqual(completed.data, {
            "status": "unavailable",
            "written_ids": [],
            "decisions": [],
        })
