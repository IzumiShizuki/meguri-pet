from __future__ import annotations

import json
import unittest

import httpx

from services.meguri_core.staging_smoke import (
    SmokeFailure,
    StagingApplicationSmoke,
    parse_sse,
)


RELEASE_ID = "meguri-staging-20260715-r003"
BUILD_ID = "meguri_v2_02c3db0c507d7c2d"


def sse(events: list[tuple[int, str, dict]]) -> str:
    return "".join(
        f"id: {sequence}\nevent: {event_type}\ndata: {json.dumps(data)}\n\n"
        for sequence, event_type, data in events
    )


class SmokeTransport:
    def __init__(self, rag_reply: str = "红色") -> None:
        self.marker = ""
        self.rag_reply = rag_reply

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if request.method == "GET" and path == "/health":
            return self._json(
                200,
                {
                    "status": "ok",
                    "build_id": BUILD_ID,
                    "llm_provider": "openai-compatible",
                    "memory_provider": "native_pgvector",
                    "rag_chunks": 12,
                },
            )
        if request.method == "GET" and path == "/health/ready":
            return self._json(
                200,
                {
                    "status": "ready",
                    "release_id": RELEASE_ID,
                    "checks": {
                        "release_identity": "passed",
                        "database_revision": "passed",
                    },
                },
            )
        if request.method == "POST" and path == "/v1/memories/review":
            body = json.loads(request.content)
            self.marker = body["candidate"]["summary"].split()[-1]
            return self._json(
                200,
                {
                    "status": "accepted",
                    "record": {
                        "memory_id": "memory-smoke",
                        "canonical_text": body["candidate"]["summary"],
                    },
                },
            )
        if request.method == "POST" and path == "/v1/chat/respond":
            return self._json(
                200,
                {
                    "build_id": BUILD_ID,
                    "memory_status": "written",
                    "response": {
                        "reply": self.marker,
                        "expression_tag": "neutral",
                        "expression_intensity": "low",
                        "voice_style": "restrained",
                        "memory_candidates": [],
                    },
                },
            )
        if request.method == "POST" and path == "/v1/turns":
            body = json.loads(request.content)
            turn_id = "turn-cancel" if "取消测试" in body["message"] else "turn-rag"
            return self._json(202, {"turn_id": turn_id})
        if request.method == "POST" and path == "/v1/turns/turn-cancel/cancel":
            return self._json(
                200,
                {"turn_id": "turn-cancel", "status": "cancel_requested"},
            )
        if request.method == "GET" and path == "/v1/turns/turn-rag":
            return self._json(200, {"turn_id": "turn-rag", "status": "completed"})
        if request.method == "GET" and path == "/v1/turns/turn-cancel":
            return self._json(
                200,
                {"turn_id": "turn-cancel", "status": "cancelled"},
            )
        if request.method == "GET" and path == "/v1/sessions/rag-smoke-fixed/events":
            events = self._rag_events()
            cursor = int(request.url.params.get("after_sequence", "0"))
            return self._sse([event for event in events if event[0] > cursor])
        if request.method == "GET" and path == "/v1/sessions/cancel-smoke-fixed/events":
            return self._sse(
                [
                    (1, "turn.started", self._envelope("turn.started", {})),
                    (
                        2,
                        "turn.cancelled",
                        self._envelope(
                            "turn.cancelled",
                            {"reason": "client_requested"},
                        ),
                    ),
                ]
            )
        raise AssertionError(f"unexpected request: {request.method} {request.url}")

    def _rag_events(self) -> list[tuple[int, str, dict]]:
        return [
            (1, "turn.started", self._envelope("turn.started", {})),
            (
                2,
                "text.delta",
                self._envelope("text.delta", {"delta": self.rag_reply}),
            ),
            (
                3,
                "memory.write.completed",
                self._envelope("memory.write.completed", {"status": "written"}),
            ),
            (
                4,
                "turn.completed",
                self._envelope("turn.completed", {"reply": self.rag_reply}),
            ),
        ]

    @staticmethod
    def _envelope(event_type: str, data: dict) -> dict:
        return {"type": event_type, "data": data}

    @staticmethod
    def _json(status: int, value: dict) -> httpx.Response:
        return httpx.Response(status, json=value)

    @staticmethod
    def _sse(events: list[tuple[int, str, dict]]) -> httpx.Response:
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream; charset=utf-8"},
            text=sse(events),
        )


class StagingApplicationSmokeTests(unittest.TestCase):
    def runner(self, transport: SmokeTransport) -> StagingApplicationSmoke:
        client = httpx.Client(
            base_url="http://staging.test",
            transport=httpx.MockTransport(transport),
        )
        self.addCleanup(client.close)
        return StagingApplicationSmoke(
            client,
            expected_release_id=RELEASE_ID,
            expected_build_id=BUILD_ID,
            poll_timeout=1,
            poll_interval=0.001,
            run_suffix="fixed",
        )

    def test_complete_smoke_passes_all_application_boundaries(self):
        result = self.runner(SmokeTransport()).run()
        self.assertEqual(result["status"], "passed")
        self.assertEqual(
            set(result["checks"]),
            {
                "provider_identity",
                "release_readiness",
                "native_memory_write",
                "schema_valid_response",
                "native_memory_recall",
                "async_turn",
                "rag_grounding",
                "sse_order",
                "sse_replay",
                "turn_cancellation",
            },
        )
        self.assertTrue(all(result["checks"].values()))

    def test_rag_answer_must_match_canonical_state(self):
        with self.assertRaisesRegex(SmokeFailure, "canonical red state"):
            self.runner(SmokeTransport(rag_reply="蓝色")).run()

    def test_invalid_sse_fails_closed(self):
        with self.assertRaisesRegex(SmokeFailure, "invalid event envelope"):
            parse_sse("event: turn.started\ndata: {}\n\n")


if __name__ == "__main__":
    unittest.main()
