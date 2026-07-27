from __future__ import annotations

from uuid import uuid4

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from starlette.requests import Request

from services.meguri_core.api_auth import (
    ApiPrincipal,
    get_api_principal,
    get_authoritative_provider,
)
from services.meguri_core.identity_api import router as identity_router
from services.meguri_core.memory_api import router as memory_router
from services.meguri_core.memory_service.enums import ActorType
from services.meguri_core.memory_service.metrics import memory_metrics
from services.meguri_core.app import secure_authoritative_turn
from services.meguri_core.schemas import TurnRequest


class ProviderStub:
    def __init__(self) -> None:
        self.candidate = None
        self.review = None
        self.binding = None
        self.hard_delete_call = None
        self.feedback = None
        self.propose_supersede_call = None
        self.direct_supersede_calls = 0
        self.raise_on_create: Exception | None = None

    async def create_candidate(self, candidate, *, request_id):
        if self.raise_on_create is not None:
            raise self.raise_on_create
        self.candidate = (candidate, request_id)
        return {"candidate_id": str(uuid4()), "status": "pending_review"}

    async def review_candidate(self, candidate_id, decision, *, actor, request_id):
        self.review = (candidate_id, decision, actor, request_id)
        return {"memory_id": str(uuid4())}

    async def search(self, query):
        return []

    async def bind_identity(self, binding, *, actor, request_id):
        self.binding = (binding, actor, request_id)
        return {"binding_id": str(uuid4())}

    async def hard_delete(self, memory_id, **kwargs):
        self.hard_delete_call = (memory_id, kwargs)
        return {
            "memory_id": str(memory_id),
            "tenant_id": kwargs["tenant_id"],
            "user_id": kwargs["user_id"],
            "deleted_versions": 1,
            "deleted_candidates": 1,
            "audit_retained": True,
        }

    async def record_feedback(self, feedback, *, request_id):
        self.feedback = (feedback, request_id)
        return {
            **feedback.model_dump(mode="json"),
            "feedback_id": str(uuid4()),
            "created_at": "2026-07-14T00:00:00Z",
        }

    async def propose_supersede(self, memory_id, update, **kwargs):
        self.propose_supersede_call = (memory_id, update, kwargs)
        return {"candidate_id": str(uuid4()), "status": "pending_review"}

    async def supersede(self, *_args, **_kwargs):
        self.direct_supersede_calls += 1
        raise AssertionError("direct supersede must not be called")


def build_client(
    provider: ProviderStub,
    principal: ApiPrincipal,
) -> TestClient:
    app = FastAPI()
    app.include_router(memory_router)
    app.include_router(identity_router)
    app.dependency_overrides[get_authoritative_provider] = lambda: provider
    app.dependency_overrides[get_api_principal] = lambda: principal
    return TestClient(app)


def user_principal(*, formal_memory_allowed: bool = True) -> ApiPrincipal:
    return ApiPrincipal(
        tenant_id="tenant-server",
        user_id="user-server",
        client_id="website",
        actor_type=ActorType.USER,
        actor_id="actor-user",
        formal_memory_allowed=formal_memory_allowed,
    )


def admin_principal() -> ApiPrincipal:
    return ApiPrincipal(
        tenant_id="tenant-admin",
        user_id="admin-user",
        client_id="admin-console",
        actor_type=ActorType.ADMIN,
        actor_id="actor-admin",
    )


def candidate_body() -> dict:
    return {
        "memory_type": "user_preference",
        "content_text": "Please remember that I prefer concise replies.",
        "confidence": 1,
        "source_session_id": "session-1",
        "source_turn_id": "turn-1",
    }


def test_candidate_requires_request_id() -> None:
    client = build_client(ProviderStub(), user_principal())

    response = client.post("/v1/memory/candidates", json=candidate_body())

    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "request_id_required"


def test_candidate_scope_and_client_are_derived_from_principal() -> None:
    provider = ProviderStub()
    client = build_client(provider, user_principal())

    response = client.post(
        "/v1/memory/candidates",
        headers={"X-Request-ID": "request-1"},
        json=candidate_body(),
    )

    assert response.status_code == 201
    candidate, request_id = provider.candidate
    assert candidate.tenant_id == "tenant-server"
    assert candidate.user_id == "user-server"
    assert candidate.source_client_id == "website"
    assert request_id == "request-1"


def test_candidate_merge_contract_is_accepted_from_the_review_entrypoint() -> None:
    provider = ProviderStub()
    client = build_client(provider, user_principal())
    base_version_id = uuid4()

    response = client.post(
        "/v1/memory/candidates",
        headers={"X-Request-ID": "request-merge"},
        json=candidate_body()
        | {
            "risk_level": "high",
            "merge_policy": "supersede",
            "base_version_id": str(base_version_id),
        },
    )

    assert response.status_code == 201
    candidate, _ = provider.candidate
    assert candidate.risk_level.value == "high"
    assert candidate.merge_policy.value == "supersede"
    assert candidate.base_version_id == base_version_id


def test_supersede_route_creates_review_candidate_without_direct_write() -> None:
    provider = ProviderStub()
    client = build_client(provider, user_principal())
    memory_id = uuid4()
    payload = {
        "content_text": "updated",
        "change_reason": "user correction",
    }

    assert client.post(
        f"/v1/memories/{memory_id}/supersede",
        headers={"X-Request-ID": "supersede-missing-base"},
        json=payload,
    ).status_code == 422

    base_version_id = uuid4()
    response = client.post(
        f"/v1/memories/{memory_id}/supersede",
        headers={"X-Request-ID": "supersede-with-base"},
        json=payload | {"base_version_id": str(base_version_id)},
    )

    assert response.status_code == 200
    assert response.json()["status"] == "pending_review"
    _, update, kwargs = provider.propose_supersede_call
    assert update.base_version_id == base_version_id
    assert kwargs["source_client_id"] == "website"
    assert kwargs["source_turn_id"] == "supersede-with-base"
    assert provider.direct_supersede_calls == 0


@pytest.mark.parametrize(
    "path,payload",
    [
        (
            "/v1/memory/candidates",
            candidate_body() | {"content_json": {"relationship_stage": "lover"}},
        ),
        (
            f"/v1/memories/{uuid4()}/supersede",
            {
                "base_version_id": str(uuid4()),
                "content_text": "updated",
                "content_json": {"nested": {"relationship-state": "lover"}},
                "change_reason": "unsafe relationship mutation",
            },
        ),
    ],
)
def test_memory_write_routes_reject_protected_relationship_fields(path, payload):
    provider = ProviderStub()
    client = build_client(provider, user_principal())

    response = client.post(
        path,
        headers={"X-Request-ID": "protected-field-attempt"},
        json=payload,
    )

    assert response.status_code == 422
    assert provider.candidate is None
    assert provider.propose_supersede_call is None


def test_unverified_identity_cannot_access_formal_memory() -> None:
    client = build_client(
        ProviderStub(), user_principal(formal_memory_allowed=False)
    )

    response = client.post(
        "/v1/memories/search",
        json={"query": "preferences"},
    )

    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "verified_binding_required"


def test_user_cannot_review_candidate() -> None:
    client = build_client(ProviderStub(), user_principal())

    response = client.post(
        f"/v1/memory/candidates/{uuid4()}/approve",
        headers={"X-Request-ID": "request-2"},
        json={"reason": "reviewed"},
    )

    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "admin_required"


def test_admin_binding_uses_authenticated_tenant_and_actor() -> None:
    provider = ProviderStub()
    client = build_client(provider, admin_principal())

    response = client.post(
        "/v1/identity-bindings",
        headers={"X-Request-ID": "request-3"},
        json={
            "user_id": "shared-user",
            "platform": "telegram",
            "platform_user_id": "platform-42",
            "verification_method": "signed_challenge",
        },
    )

    assert response.status_code == 201
    binding, actor, request_id = provider.binding
    assert binding.tenant_id == "tenant-admin"
    assert binding.user_id == "shared-user"
    assert actor.actor_id == "actor-admin"
    assert request_id == "request-3"


def test_untrusted_scope_fields_are_rejected() -> None:
    client = build_client(ProviderStub(), user_principal())

    response = client.post(
        "/v1/memory/candidates",
        headers={"X-Request-ID": "request-extra"},
        json=candidate_body() | {"tenant_id": "tenant-attacker"},
    )

    assert response.status_code == 422


def test_provider_exception_is_sanitized() -> None:
    provider = ProviderStub()
    provider.raise_on_create = RuntimeError(
        "postgresql://memory_admin:super-secret@db.internal/memory"
    )
    client = build_client(provider, user_principal())

    response = client.post(
        "/v1/memory/candidates",
        headers={"X-Request-ID": "request-4"},
        json=candidate_body(),
    )

    assert response.status_code == 503
    body = response.json()
    assert body["detail"] == {
        "code": "memory_unavailable",
        "message": "memory service is temporarily unavailable",
    }
    assert "super-secret" not in response.text
    assert "db.internal" not in response.text


def test_hard_delete_is_admin_only_and_target_is_explicit() -> None:
    memory_id = uuid4()
    provider = ProviderStub()
    user_client = build_client(provider, user_principal())
    body = {
        "user_id": "target-user",
        "reason": "approved erasure request",
        "confirmation": f"HARD_DELETE:{memory_id}",
    }

    denied = user_client.post(
        f"/v1/admin/memories/{memory_id}/hard-delete",
        headers={"X-Request-ID": "hard-delete-denied"},
        json=body,
    )
    assert denied.status_code == 403

    admin_client = build_client(provider, admin_principal())
    accepted = admin_client.post(
        f"/v1/admin/memories/{memory_id}/hard-delete",
        headers={"X-Request-ID": "hard-delete-1"},
        json=body,
    )
    assert accepted.status_code == 200
    _, kwargs = provider.hard_delete_call
    assert kwargs["tenant_id"] == "tenant-admin"
    assert kwargs["user_id"] == "target-user"
    assert kwargs["actor"].actor_type is ActorType.ADMIN


def test_feedback_scope_is_derived_and_false_recall_is_typed() -> None:
    provider = ProviderStub()
    client = build_client(provider, user_principal())
    memory_id = uuid4()
    version_id = uuid4()

    response = client.post(
        f"/v1/memories/{memory_id}/feedback",
        headers={"X-Request-ID": "feedback-1"},
        json={
            "version_id": str(version_id),
            "feedback_kind": "false_recall",
            "query_text": "tea",
            "hit_rank": 1,
        },
    )

    assert response.status_code == 201
    feedback, request_id = provider.feedback
    assert feedback.tenant_id == "tenant-server"
    assert feedback.user_id == "user-server"
    assert feedback.memory_id == memory_id
    assert feedback.version_id == version_id
    assert feedback.feedback_kind.value == "false_recall"
    assert request_id == "feedback-1"


def test_metrics_have_required_unlabelled_series() -> None:
    rendered = memory_metrics.render()

    for metric in (
        "memory_candidate_created_total",
        "memory_candidate_approved_total",
        "memory_candidate_rejected_total",
        "memory_embedding_failure_total",
        "memory_conflict_total",
        "memory_false_recall_feedback_total",
        "memory_provider_failure_total",
        "memory_active_total",
        "memory_search_latency_ms",
        "memory_search_result_count",
        "memory_embedding_queue_depth",
    ):
        assert metric in rendered
    assert "tenant-server" not in rendered
    assert "user-server" not in rendered


@pytest.mark.asyncio
async def test_authoritative_chat_scope_comes_only_from_authenticated_principal() -> None:
    request = Request({"type": "http", "headers": []})
    request.state.meguri_principal = ApiPrincipal(
        tenant_id="tenant-a",
        user_id="trusted-user",
        client_id="airi",
        session_id="trusted-session",
        actor_id="trusted-user",
        formal_memory_allowed=False,
    )
    untrusted = TurnRequest(
        user_id="attacker-selected-user",
        client_id="website",
        session_id="attacker-selected-session",
        message="hello",
    )

    secured = await secure_authoritative_turn(
        request,
        untrusted,
        memory_provider=type(
            "NativeMarker", (), {"provider_name": "native_pgvector"}
        )(),
    )

    assert secured.user_id == "trusted-user"
    assert secured.client_id == "airi"
    assert secured.session_id == "trusted-session"
    assert secured.formal_memory_allowed is False


@pytest.mark.asyncio
async def test_trusted_identity_headers_cannot_grant_formal_memory(monkeypatch) -> None:
    monkeypatch.setenv("MEGURI_ALLOW_TRUSTED_IDENTITY_HEADERS", "true")
    headers = {
        "X-Meguri-Tenant-ID": "tenant-a",
        "X-Meguri-User-ID": "header-user",
        "X-Meguri-Client-ID": "astrbot",
        "X-Meguri-Actor-ID": "header-user",
        "X-Meguri-Session-ID": "header-session",
        "X-Meguri-Formal-Memory-Allowed": "true",
    }
    request = Request(
        {
            "type": "http",
            "headers": [
                (name.lower().encode("ascii"), value.encode("ascii"))
                for name, value in headers.items()
            ],
        }
    )

    principal = await get_api_principal(request)

    assert principal.user_id == "header-user"
    assert principal.formal_memory_allowed is False


@pytest.mark.asyncio
async def test_authoritative_chat_rejects_cross_tenant_principal() -> None:
    request = Request({"type": "http", "headers": []})
    request.state.meguri_principal = ApiPrincipal(
        tenant_id="tenant-b",
        user_id="trusted-user",
        client_id="website",
        session_id="trusted-session",
        actor_id="trusted-user",
    )

    with pytest.raises(HTTPException) as caught:
        await secure_authoritative_turn(
            request,
            TurnRequest(
                user_id="ignored",
                client_id="website",
                session_id="ignored",
                message="hello",
            ),
            memory_provider=type(
                "NativeMarker",
                (),
                {"provider_name": "native_pgvector", "tenant_id": "tenant-a"},
            )(),
        )

    assert caught.value.status_code == 403
    assert caught.value.detail["code"] == "tenant_not_allowed"
