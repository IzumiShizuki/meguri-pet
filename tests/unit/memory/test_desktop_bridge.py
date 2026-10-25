from types import SimpleNamespace
from uuid import uuid4
from datetime import datetime, timezone

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from services.meguri_core.desktop_memory_bridge import router
from services.meguri_core.memory import FakeMemoryProvider
from services.meguri_core.memory_service.native_pgvector import NativePgvectorMemoryProvider
from services.meguri_core.memory_service.models import MemoryCandidate, MemoryItem, MemoryVersion
from services.meguri_core.memory_service.enums import ActorType, CandidateStatus
from services.meguri_core.memory_service.contracts import MemoryNotFoundError


class NativeStub(NativePgvectorMemoryProvider):
    def __init__(self):
        super().__init__(service=SimpleNamespace(uow_factory=lambda: None), tenant_id="tenant-a")
        self.candidates = {}
        self.summaries = {}
        self.reviewed = []
        self.deleted = []
        self.submitted = {}
        self.records = {}
        self.corrections = []

    async def health(self):
        return {"status": "ok", "provider": "native_pgvector"}

    async def list_candidates(self, *, tenant_id, user_id, status=None):
        return [candidate for candidate in self.candidates.values() if candidate.tenant_id == tenant_id and candidate.user_id == user_id]

    async def review_candidate(self, candidate_id, decision, *, actor, request_id):
        self.reviewed.append((candidate_id, actor))
        return None

    async def get(self, memory_id, *, tenant_id, user_id):
        item = self.records.get(memory_id)
        if item is None or item.user_id != user_id or item.tenant_id != tenant_id:
            raise MemoryNotFoundError("foreign item")
        return item

    async def propose_supersede(self, memory_id, update, **kwargs):
        self.corrections.append((memory_id, update, kwargs))
        return await super().propose_supersede(memory_id, update, **kwargs)

    async def delete(self, memory_id, **kwargs):
        self.deleted.append((memory_id, kwargs))

    async def summarize_session(self, summary, *, request_id):
        self.summaries.setdefault(request_id, summary)
        return self.summaries[request_id]

    async def submit_runtime_candidate(self, candidate, **kwargs):
        request_id = kwargs["request_id"]
        self.submitted.setdefault(request_id, SimpleNamespace(candidate_id=uuid4()))
        return self.submitted[request_id]


@pytest.fixture
def setup(monkeypatch):
    monkeypatch.setenv("MEGURI_INTERNAL_BRIDGE_TOKEN", "desktop-bridge-test")
    monkeypatch.delenv("MEGURI_INTERNAL_BRIDGE_TOKEN_FILE", raising=False)
    monkeypatch.setenv("MEGURI_INTERNAL_BRIDGE_ALLOW_TESTCLIENT", "true")
    provider = NativeStub()
    app = FastAPI()
    app.include_router(router)
    app.state.orchestrator = SimpleNamespace(memory=provider)
    return TestClient(app), provider, app


def scope(**overrides):
    return {"tenant_id": "tenant-a", "user_id": "owner", "client_id": "desktop_pet", "session_id": "session-a", **overrides}


def post(client, route, body):
    return client.post("/internal/memory/desktop/" + route, headers={"X-Meguri-Internal-Token": "desktop-bridge-test"}, json=body)


def test_status_requires_token_and_native_provider(setup, monkeypatch):
    client, _, app = setup
    assert client.post("/internal/memory/desktop/status", json=scope()).status_code == 503
    ready = post(client, "status", scope())
    assert ready.json()["capabilities"] == ["memory_detail", "memory_corrections"]
    app.state.orchestrator.memory = FakeMemoryProvider()
    result = post(client, "status", scope())
    assert result.status_code == 503
    assert result.json()["detail"]["code"] == "authoritative_provider_required"
    monkeypatch.delenv("MEGURI_INTERNAL_BRIDGE_TOKEN")
    assert post(client, "status", scope()).status_code == 503


def test_tenant_and_actor_fields_cannot_override_server_authority(setup):
    client, provider, _ = setup
    assert post(client, "status", scope(tenant_id="foreign")).status_code == 403
    assert post(client, "review", scope(candidate_id=str(uuid4()), decision="approve", request_id="request-1", actor_id="admin")).status_code == 422
    assert provider.reviewed == []


def test_foreign_candidate_and_memory_are_never_mutated(setup):
    client, provider, _ = setup
    candidate_id = uuid4()
    provider.candidates[candidate_id] = MemoryCandidate(candidate_id=candidate_id, tenant_id="tenant-a", user_id="other-user",
        memory_type="user_preference", content_text="Tea preference", confidence=.9,
        source_client_id="desktop_pet", source_session_id="session-foreign", source_turn_id="turn-1", status=CandidateStatus.PENDING_REVIEW)
    assert post(client, "review", scope(candidate_id=str(candidate_id), decision="approve", request_id="review-1")).status_code == 404
    assert post(client, "delete", scope(user_id="other-user", memory_id=str(uuid4()), request_id="delete-1")).status_code == 404
    assert provider.reviewed == provider.deleted == []


def test_repeated_checkpoint_uses_persisted_first_summary_and_candidates(setup):
    client, provider, _ = setup
    body = scope(request_id="summary-1", checkpoint=20, messages=[{"role": "user", "content": "I prefer tea"}, {"role": "assistant", "content": "Understood"}],
        structured_candidates=[{"type": "preference", "summary": "User prefers tea", "confidence": .9}])
    first = post(client, "summary", body)
    assert first.status_code == 200
    body["structured_candidates"][0]["summary"] = "A changed model extraction"
    body["messages"][0]["content"] = "Later content"
    second = post(client, "summary", body)
    assert second.status_code == 200
    assert second.json() == first.json()
    assert len(provider.summaries) == len(provider.submitted) == 1


def test_summary_rejects_unbounded_history(setup):
    client, _, _ = setup
    assert post(client, "summary", scope(request_id="too-big", checkpoint=1, messages=[{"role": "user", "content": "test"}] * 21)).status_code == 422


def memory_item(**overrides):
    now = datetime.now(timezone.utc)
    memory_id = uuid4()
    version = MemoryVersion(version_id=uuid4(), memory_id=memory_id, version_no=2,
        content_text="用户喜欢茶", change_reason="已确认", created_by_type=ActorType.USER,
        created_by_id="private-actor", created_at=now, content_json={"private": "hidden-structured-data"},
        provenance={"source_client_id": "desktop_pet", "source_session_id": "source-session",
                    "source_kind": "direct_user", "raw_message": "hidden-source-content"})
    return MemoryItem(memory_id=memory_id, tenant_id="tenant-a", user_id="owner",
        memory_type="user_preference", scope="global_user", status="active",
        current_version_id=version.version_id, current_version=version, importance=.5, confidence=.9,
        created_at=now, updated_at=now, **overrides)


def test_detail_exposes_only_owner_display_projection(setup):
    client, provider, _ = setup
    item = memory_item()
    provider.records[item.memory_id] = item
    result = post(client, "detail", scope(memory_id=str(item.memory_id)))
    assert result.status_code == 200
    detail = result.json()
    assert detail["version_no"] == 2
    assert detail["content_text"] == "用户喜欢茶"
    assert detail["can_correct"] is True
    assert detail["source"] == {"client_id": "desktop_pet", "session_id": "source-session", "kind": "direct_user"}
    assert "hidden" not in result.text and "private-actor" not in result.text
    assert "provenance" not in result.text and "content_json" not in result.text
    assert post(client, "detail", scope(user_id="other-user", memory_id=str(item.memory_id))).status_code == 404
    assert post(client, "detail", scope(memory_id=str(item.memory_id), actor_id="admin")).status_code == 422


def test_detail_handles_missing_provenance_and_version(setup):
    client, provider, _ = setup
    item = memory_item()
    item.current_version = None
    provider.records[item.memory_id] = item
    result = post(client, "detail", scope(memory_id=str(item.memory_id))).json()
    assert result["source"] == {"client_id": None, "session_id": None, "kind": None}
    assert result["can_correct"] is False
    assert result["version_no"] is None


@pytest.mark.parametrize("fields", [
    {"content_text": " "}, {"content_text": "x" * 4001}, {"content_text": 123},
    {"change_reason": ""}, {"change_reason": "x" * 1001}, {"base_version_id": "wrong"},
    {"actor_id": "admin"}, {"content_json": {"relationship_level": "trusted"}},
])
def test_correction_rejects_invalid_and_untrusted_fields(setup, fields):
    client, provider, _ = setup
    body = scope(memory_id=str(uuid4()), base_version_id=str(uuid4()), content_text="用户喜欢咖啡",
                 change_reason="更新偏好", request_id="correction-invalid")
    body.update(fields)
    assert post(client, "correct", body).status_code == 422
    assert provider.corrections == []
