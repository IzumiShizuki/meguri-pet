import unittest

from fastapi.testclient import TestClient

from services.meguri_core.app import app, orchestrator


client = TestClient(app)


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
    def setUp(self):
        orchestrator.events.clear()
        orchestrator.memory.records.clear()
        orchestrator.state_machine.overrides.clear()

    def test_sync_contract_and_build_id(self):
        response = client.post("/v1/chat/respond", json=request_payload())
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["build_id"].startswith("meguri_"))
        self.assertEqual(set(body["response"]), {"reply", "expression_tag", "expression_intensity", "voice_style", "memory_candidates"})
        self.assertIn(body["expression"]["outfit_code"], {"01", "02", "03", "04"})
        self.assertIsNotNone(body["expression"]["sprite_file"])
        self.assertGreater(client.get("/health").json()["rag_chunks"], 0)

    def test_event_order_and_sse_replay(self):
        created = client.post("/v1/turns", json=request_payload()).json()
        events = orchestrator.events["s-test"]
        self.assertEqual(events[0].type, "turn.started")
        self.assertEqual(events[-1].type, "turn.completed")
        self.assertEqual([e.sequence for e in events], list(range(1, len(events) + 1)))
        stream = client.get("/v1/sessions/s-test/events")
        self.assertEqual(stream.status_code, 200)
        self.assertIn("text.delta", stream.text)
        self.assertEqual(created["turn_id"], events[-1].turn_id)

    def test_capability_boundary_for_voice_and_screen(self):
        response = client.post("/v1/chat/respond", json=request_payload(client_id="astrbot", client_capabilities={"text": True, "sprite": False, "voice": True, "screen_context": True}))
        state = response.json()["runtime_state"]
        self.assertFalse(state["voice_enabled"])
        self.assertFalse(state["screen_context_enabled"])

    def test_memory_is_shared_by_user_but_not_session_events(self):
        client.post("/v1/chat/respond", json=request_payload(message="我喜欢喝茶"))
        memories = client.get("/v1/memories", params={"user_id": "u-test"}).json()["items"]
        self.assertTrue(memories)
        client.post("/v1/chat/respond", json=request_payload(session_id="other-session", message="还记得我吗"))
        self.assertNotIn("other-session", [e.session_id for e in orchestrator.events["s-test"]])
