from __future__ import annotations

from uuid import uuid4

from fastapi import FastAPI
from fastapi.testclient import TestClient

from services.meguri_core.api_auth import (
    ApiPrincipal,
    get_api_principal,
    get_authoritative_provider,
)
from services.meguri_core.identity_api import router as identity_router
from services.meguri_core.memory_api import router as memory_router
from services.meguri_core.memory_service.enums import ActorType
from services.meguri_core.memory_service.metrics import memory_metrics


class ProviderStub:
    def __init__(self) -> None:
        self.candidate = None
        self.review = None
        self.binding = None
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
