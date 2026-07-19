import os
import unittest

from fastapi.testclient import TestClient

from services.meguri_core.app import app, orchestrator
from services.meguri_core.memory import FakeMemoryProvider


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

    def test_bridge_search_and_write_use_python_provider(self) -> None:
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
        self.assertEqual(write.status_code, 200)
        self.assertEqual(write.json()["status"], "written")
        search = self.client.post(
            "/internal/memory/search",
            headers=headers,
            json={"user_id": "bridge-user", "query": "喝茶"},
        )
        self.assertEqual(search.status_code, 200)
        self.assertTrue(search.json()["items"])
