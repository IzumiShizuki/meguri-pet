import unittest
import time

from fastapi.testclient import TestClient

from services.meguri_core.app import app, orchestrator


def request_payload(**overrides):
    payload = {
        "user_id": "u-test",
        "client_id": "website",
        "session_id": "s-test",
        "message": "你好，今天继续处理项目。",
        "client_capabilities": {"text": True, "sprite": True, "voice": False, "screen_context": False},
    }
    payload.update(overrides)
    return payload


class MeguriCoreTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)
        cls.client.__enter__()

    @classmethod
    def tearDownClass(cls):
        cls.client.__exit__(None, None, None)

    def setUp(self):
        orchestrator.reset()

    def wait_for_terminal(self, turn_id: str, timeout: float = 2.0) -> dict:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            status = self.client.get(f"/v1/turns/{turn_id}").json()
            if status["status"] in {"completed", "failed", "cancelled"}:
                return status
            time.sleep(0.01)
        self.fail(f"turn {turn_id} did not reach a terminal state")

    def test_sync_contract_and_build_id(self):
        response = self.client.post("/v1/chat/respond", json=request_payload())
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["build_id"].startswith("meguri_"))
        self.assertEqual(set(body["response"]), {"reply", "expression_tag", "expression_intensity", "voice_style", "memory_candidates"})
        self.assertIn(body["expression"]["outfit_code"], {"01", "02", "03", "04"})
        self.assertIsNotNone(body["expression"]["sprite_file"])
        self.assertGreater(self.client.get("/health").json()["rag_chunks"], 0)

    def test_event_order_and_sse_replay(self):
        created_response = self.client.post("/v1/turns", json=request_payload())
        self.assertEqual(created_response.status_code, 202)
        created = created_response.json()
        self.wait_for_terminal(created["turn_id"])
        events = orchestrator.events["s-test"]
        self.assertEqual(events[0].type, "turn.started")
        self.assertEqual(events[-1].type, "turn.completed")
        self.assertEqual([e.sequence for e in events], list(range(1, len(events) + 1)))
        stream = self.client.get("/v1/sessions/s-test/events")
        self.assertEqual(stream.status_code, 200)
        self.assertIn("text.delta", stream.text)
        self.assertEqual(created["turn_id"], events[-1].turn_id)

    def test_idempotency_key_reuses_turn(self):
        headers = {"Idempotency-Key": "same-request"}
        first = self.client.post("/v1/turns", json=request_payload(message="idempotent" * 40), headers=headers).json()
        second = self.client.post("/v1/turns", json=request_payload(message="idempotent" * 40), headers=headers).json()
        self.assertEqual(first["turn_id"], second["turn_id"])
        self.wait_for_terminal(first["turn_id"])
        started = [event for event in orchestrator.events["s-test"] if event.type == "turn.started"]
        self.assertEqual(len(started), 1)

    def test_cancel_running_turn(self):
        created = self.client.post("/v1/turns", json=request_payload(message="cancel-me " * 200)).json()
        cancelled = self.client.post(f"/v1/turns/{created['turn_id']}/cancel")
        self.assertEqual(cancelled.status_code, 200)
        status = self.wait_for_terminal(created["turn_id"])
        self.assertEqual(status["status"], "cancelled")
        event_types = [event.type for event in orchestrator.events["s-test"]]
        self.assertIn("turn.cancelled", event_types)
        self.assertNotIn("turn.completed", event_types)

    def test_reconnect_replays_only_newer_sequences(self):
        created = self.client.post("/v1/turns", json=request_payload()).json()
        self.wait_for_terminal(created["turn_id"])
        events = orchestrator.events["s-test"]
        cursor = events[len(events) // 2].sequence
        stream = self.client.get("/v1/sessions/s-test/events", params={"after_sequence": cursor})
        replayed_ids = [int(line.removeprefix("id: ")) for line in stream.text.splitlines() if line.startswith("id: ")]
        self.assertTrue(replayed_ids)
        self.assertTrue(all(sequence > cursor for sequence in replayed_ids))

    def test_capability_boundary_for_voice_and_screen(self):
        response = self.client.post("/v1/chat/respond", json=request_payload(client_id="astrbot", client_capabilities={"text": True, "sprite": False, "voice": True, "screen_context": True}))
        state = response.json()["runtime_state"]
        self.assertFalse(state["voice_enabled"])
        self.assertFalse(state["screen_context_enabled"])

    def test_memory_is_shared_by_user_but_not_session_events(self):
        self.client.post("/v1/chat/respond", json=request_payload(message="我喜欢喝茶"))
        memories = self.client.get("/v1/memories", params={"user_id": "u-test"}).json()["items"]
        self.assertTrue(memories)
        self.client.post("/v1/chat/respond", json=request_payload(session_id="other-session", message="还记得我吗"))
        self.assertNotIn("other-session", [e.session_id for e in orchestrator.events["s-test"]])

    def test_runtime_override_rejects_disabled_outfits_and_naive_expiry(self):
        disabled = self.client.post("/v1/runtime/override", params={"user_id": "u-test"}, json={"outfit_code": "07"})
        self.assertEqual(disabled.status_code, 422)
        naive = self.client.post("/v1/runtime/override", params={"user_id": "u-test"}, json={"expires_at": "2026-07-13T10:00:00"})
        self.assertEqual(naive.status_code, 422)
