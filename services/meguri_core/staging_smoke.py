"""Run the authenticated Meguri application smoke from inside Staging core."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

import httpx


TERMINAL_STATUSES = {"completed", "failed", "cancelled"}
RESPONSE_FIELDS = {
    "reply",
    "expression_tag",
    "expression_intensity",
    "voice_style",
    "memory_candidates",
}


class SmokeFailure(RuntimeError):
    """A sanitized application-level smoke failure."""


def parse_sse(value: str) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    normalized = value.replace("\r\n", "\n")
    for block in normalized.split("\n\n"):
        fields: dict[str, str] = {}
        for line in block.splitlines():
            if not line or line.startswith(":") or ":" not in line:
                continue
            key, raw = line.split(":", 1)
            fields[key] = raw.lstrip()
        if not fields:
            continue
        try:
            sequence = int(fields["id"])
            event_type = fields["event"]
            data = json.loads(fields["data"])
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise SmokeFailure("SSE stream contains an invalid event envelope") from exc
        if not isinstance(data, dict):
            raise SmokeFailure("SSE event data must be an object")
        events.append({"sequence": sequence, "type": event_type, "data": data})
    return events


class StagingApplicationSmoke:
    def __init__(
        self,
        client: httpx.Client,
        *,
        expected_release_id: str,
        expected_build_id: str,
        expected_llm_provider: str = "openai-compatible",
        expected_memory_provider: str = "native_pgvector",
        poll_timeout: float = 180.0,
        poll_interval: float = 0.25,
        run_suffix: str | None = None,
    ) -> None:
        if poll_timeout <= 0 or poll_interval <= 0:
            raise ValueError("poll timeouts must be positive")
        self.client = client
        self.expected_release_id = expected_release_id
        self.expected_build_id = expected_build_id
        self.expected_llm_provider = expected_llm_provider
        self.expected_memory_provider = expected_memory_provider
        self.poll_timeout = poll_timeout
        self.poll_interval = poll_interval
        self.run_suffix = run_suffix

    def run(self) -> dict[str, Any]:
        checks: dict[str, bool] = {}
        health = self._json("GET", "/health", expected_status=200)
        self._require(health.get("status") == "ok", "health status is not ok")
        self._require(
            health.get("build_id") == self.expected_build_id,
            "health build ID does not match the expected data build",
        )
        self._require(
            health.get("llm_provider") == self.expected_llm_provider,
            "health LLM provider identity does not match",
        )
        self._require(
            health.get("memory_provider") == self.expected_memory_provider,
            "health Memory provider identity does not match",
        )
        self._require(
            isinstance(health.get("rag_chunks"), int) and health["rag_chunks"] > 0,
            "RAG corpus is empty",
        )
        checks["provider_identity"] = True

        ready = self._json("GET", "/health/ready", expected_status=200)
        self._require(ready.get("status") == "ready", "readiness status is not ready")
        self._require(
            ready.get("release_id") == self.expected_release_id,
            "readiness release ID does not match",
        )
        readiness_checks = ready.get("checks")
        self._require(
            isinstance(readiness_checks, dict)
            and readiness_checks
            and all(value == "passed" for value in readiness_checks.values()),
            "readiness contains a failed identity check",
        )
        checks["release_readiness"] = True

        suffix = self.run_suffix or uuid4().hex[:12]
        user_id = f"environment-smoke-{suffix}"
        memory_session = f"memory-smoke-{suffix}"
        marker = f"meguri-smoke-{suffix}"
        reviewed = self._json(
            "POST",
            "/v1/memories/review",
            expected_status=200,
            json={
                "user_id": user_id,
                "candidate": {
                    "type": "project",
                    "summary": f"长期项目代号是 {marker}",
                    "confidence": 0.99,
                    "sensitivity": "normal",
                    "source_scope": "current_message",
                },
                "decision": "accept",
                "source_client": "environment-smoke",
                "source_session": memory_session,
            },
        )
        record = reviewed.get("record")
        self._require(
            reviewed.get("status") == "accepted"
            and isinstance(record, dict)
            and marker in str(record.get("canonical_text", "")),
            "native MemoryProvider did not persist the synthetic project memory",
        )
        checks["native_memory_write"] = True

        recall = self._json(
            "POST",
            "/v1/chat/respond",
            expected_status=200,
            json=self._turn_payload(
                user_id,
                memory_session,
                "请根据 long_term_memories 只回答我已记录的长期项目代号。",
            ),
        )
        self._validate_chat_response(recall)
        self._require(
            recall.get("memory_status") != "unavailable",
            "Turn reported native memory as unavailable",
        )
        reply = str((recall.get("response") or {}).get("reply", ""))
        self._require(
            marker.casefold() in reply.casefold(),
            "LLM did not recall the synthetic project marker",
        )
        checks["schema_valid_response"] = True
        checks["native_memory_recall"] = True

        rag_session = f"rag-smoke-{suffix}"
        idempotency_key = f"rag-{suffix}"
        rag_payload = self._turn_payload(
            user_id,
            rag_session,
            "根据 canon_examples，只回答命运恋爱测量器变成什么颜色表示缘分开始成立？",
        )
        created = self._json(
            "POST",
            "/v1/turns",
            expected_status=202,
            headers={"Idempotency-Key": idempotency_key},
            json=rag_payload,
        )
        repeated = self._json(
            "POST",
            "/v1/turns",
            expected_status=202,
            headers={"Idempotency-Key": idempotency_key},
            json=rag_payload,
        )
        turn_id = self._turn_id(created)
        self._require(
            self._turn_id(repeated) == turn_id,
            "idempotency key created a duplicate Turn",
        )
        terminal = self._wait_for_terminal(turn_id)
        self._require(terminal.get("status") == "completed", "RAG Turn did not complete")
        checks["async_turn"] = True

        events = self._events(rag_session)
        self._validate_event_order(events, expected_terminal="turn.completed")
        completed = next(event for event in events if event["type"] == "turn.completed")
        completed_reply = str((completed["data"].get("data") or {}).get("reply", ""))
        self._require(
            "红" in completed_reply,
            "RAG-grounded answer did not identify the canonical red state",
        )
        memory_events = [
            event for event in events if event["type"] == "memory.write.completed"
        ]
        self._require(memory_events, "Turn did not emit the memory completion boundary")
        memory_data = memory_events[-1]["data"].get("data") or {}
        self._require(
            memory_data.get("status") != "unavailable",
            "Turn memory boundary is unavailable",
        )
        checks["rag_grounding"] = True
        checks["sse_order"] = True

        cursor = events[len(events) // 2]["sequence"]
        replay = self._events(rag_session, after_sequence=cursor)
        expected_replay = [event for event in events if event["sequence"] > cursor]
        self._require(replay == expected_replay and replay, "SSE reconnect replay is incomplete")
        checks["sse_replay"] = True

        cancel_session = f"cancel-smoke-{suffix}"
        cancel_created = self._json(
            "POST",
            "/v1/turns",
            expected_status=202,
            json=self._turn_payload(
                user_id,
                cancel_session,
                "这是取消测试。请在读取上下文后生成一个简短回复。",
            ),
        )
        cancel_turn_id = self._turn_id(cancel_created)
        cancelled = self._json(
            "POST",
            f"/v1/turns/{cancel_turn_id}/cancel",
            expected_status=200,
        )
        self._require(
            cancelled.get("status") in {"cancel_requested", "cancelled"},
            "Turn cancellation was not accepted",
        )
        cancel_terminal = self._wait_for_terminal(cancel_turn_id)
        self._require(
            cancel_terminal.get("status") == "cancelled",
            "cancelled Turn reached the wrong terminal state",
        )
        cancel_events = self._events(cancel_session)
        self._validate_event_order(cancel_events, expected_terminal="turn.cancelled")
        self._require(
            not any(event["type"] == "turn.completed" for event in cancel_events),
            "cancelled Turn also emitted completion",
        )
        checks["turn_cancellation"] = True

        return {
            "smoke_schema_version": 1,
            "status": "passed",
            "environment": "staging",
            "release_id": self.expected_release_id,
            "build_id": self.expected_build_id,
            "checks": checks,
            "generated_at": datetime.now(timezone.utc).isoformat(),
        }

    @staticmethod
    def _turn_payload(user_id: str, session_id: str, message: str) -> dict[str, Any]:
        return {
            "user_id": user_id,
            "client_id": "environment-smoke",
            "session_id": session_id,
            "message": message,
            "client_capabilities": {
                "text": True,
                "sprite": False,
                "voice": False,
                "screen_context": False,
            },
        }

    def _validate_chat_response(self, value: dict[str, Any]) -> None:
        self._require(
            value.get("build_id") == self.expected_build_id,
            "response build ID does not match",
        )
        response = value.get("response")
        self._require(isinstance(response, dict), "response payload is missing")
        self._require(
            set(response) == RESPONSE_FIELDS,
            "response payload fields do not match the schema",
        )
        self._require(
            isinstance(response.get("reply"), str) and response["reply"],
            "response reply is empty",
        )

    def _turn_id(self, value: dict[str, Any]) -> str:
        turn_id = value.get("turn_id")
        self._require(isinstance(turn_id, str) and turn_id, "Turn response has no ID")
        return str(turn_id)

    def _wait_for_terminal(self, turn_id: str) -> dict[str, Any]:
        deadline = time.monotonic() + self.poll_timeout
        while time.monotonic() < deadline:
            status = self._json(
                "GET",
                f"/v1/turns/{turn_id}",
                expected_status=200,
            )
            if status.get("status") in TERMINAL_STATUSES:
                if status.get("status") == "failed":
                    raise SmokeFailure("Turn failed during application smoke")
                return status
            time.sleep(self.poll_interval)
        raise SmokeFailure("Turn did not reach a terminal state before timeout")

    def _events(
        self,
        session_id: str,
        after_sequence: int = 0,
    ) -> list[dict[str, Any]]:
        response = self.client.get(
            f"/v1/sessions/{session_id}/events",
            params={"after_sequence": after_sequence},
        )
        if response.status_code != 200:
            raise SmokeFailure(f"SSE request returned HTTP {response.status_code}")
        if "text/event-stream" not in response.headers.get("content-type", ""):
            raise SmokeFailure("SSE endpoint returned the wrong content type")
        return parse_sse(response.text)

    def _validate_event_order(
        self,
        events: list[dict[str, Any]],
        *,
        expected_terminal: str,
    ) -> None:
        self._require(events, "SSE stream is empty")
        sequences = [event["sequence"] for event in events]
        self._require(
            sequences == list(range(1, len(events) + 1)),
            "SSE sequences are not contiguous",
        )
        self._require(
            events[0]["type"] == "turn.started",
            "SSE stream does not start with turn.started",
        )
        self._require(
            events[-1]["type"] == expected_terminal,
            "SSE stream has the wrong terminal event",
        )

    def _json(
        self,
        method: str,
        path: str,
        *,
        expected_status: int,
        **kwargs: Any,
    ) -> dict[str, Any]:
        response = self.client.request(method, path, **kwargs)
        if response.status_code != expected_status:
            raise SmokeFailure(f"{method} {path} returned HTTP {response.status_code}")
        try:
            value = response.json()
        except ValueError as exc:
            raise SmokeFailure(f"{method} {path} returned invalid JSON") from exc
        if not isinstance(value, dict):
            raise SmokeFailure(f"{method} {path} did not return an object")
        return value

    @staticmethod
    def _require(condition: bool, message: str) -> None:
        if not condition:
            raise SmokeFailure(message)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument(
        "--expected-release-id",
        default=os.getenv("MEGURI_RELEASE_ID", ""),
    )
    parser.add_argument(
        "--expected-build-id",
        default=os.getenv("MEGURI_DATA_BUILD_ID", ""),
    )
    parser.add_argument("--expected-llm-provider", default="openai-compatible")
    parser.add_argument("--expected-memory-provider", default="native_pgvector")
    parser.add_argument("--request-timeout", type=float, default=90.0)
    parser.add_argument("--poll-timeout", type=float, default=180.0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    if not args.expected_release_id or not args.expected_build_id:
        print(
            "staging_application_smoke_failed: expected release and build IDs are required",
            file=sys.stderr,
        )
        return 2
    try:
        with httpx.Client(
            base_url=args.base_url,
            timeout=args.request_timeout,
            follow_redirects=False,
            trust_env=False,
        ) as client:
            result = StagingApplicationSmoke(
                client,
                expected_release_id=args.expected_release_id,
                expected_build_id=args.expected_build_id,
                expected_llm_provider=args.expected_llm_provider,
                expected_memory_provider=args.expected_memory_provider,
                poll_timeout=args.poll_timeout,
            ).run()
        rendered = json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        if args.output is not None:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0
    except (SmokeFailure, httpx.HTTPError, OSError, ValueError) as exc:
        detail = str(exc) if isinstance(exc, SmokeFailure) else type(exc).__name__
        print(f"staging_application_smoke_failed: {detail}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
