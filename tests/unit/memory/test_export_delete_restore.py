from __future__ import annotations

import json
from copy import deepcopy
from datetime import datetime, timezone
from uuid import uuid4

import pytest

from services.meguri_core.memory_service.contracts import (
    MemoryAuthorizationError,
    MemoryStateError,
)
from services.meguri_core.memory_service.enums import (
    ActorType,
    MemoryScope,
    MemoryStatus,
    MemoryType,
)
from services.meguri_core.memory_service.export import render_memory_export_jsonl
from services.meguri_core.memory_service.models import (
    MemoryActor,
    MemoryItem,
    MemoryVersion,
)
from services.meguri_core.memory_service.service import MemoryService


NOW = datetime(2026, 7, 14, tzinfo=timezone.utc)


def version(memory_id, version_no, text, *, supersedes=None) -> MemoryVersion:
    return MemoryVersion(
        version_id=uuid4(),
        memory_id=memory_id,
        version_no=version_no,
        content_text=text,
        change_reason="approved" if version_no == 1 else "user correction",
        provenance={"source_turn_id": f"turn-{version_no}"},
        created_by_type=ActorType.USER,
        created_by_id="user-a",
        created_at=NOW,
        supersedes_version_id=supersedes,
    )


def active_item() -> tuple[MemoryItem, list[MemoryVersion]]:
    memory_id = uuid4()
    first = version(memory_id, 1, "User prefers tea")
    second = version(
        memory_id,
        2,
        "User prefers coffee",
        supersedes=first.version_id,
    )
    return (
        MemoryItem(
            memory_id=memory_id,
            tenant_id="tenant-a",
            user_id="user-a",
            memory_type=MemoryType.USER_PREFERENCE,
            scope=MemoryScope.GLOBAL_USER,
            status=MemoryStatus.ACTIVE,
            current_version_id=second.version_id,
            importance=0.8,
            confidence=0.9,
            created_at=NOW,
            updated_at=NOW,
            current_version=second,
        ),
        [first, second],
    )


class LifecycleRepository:
    def __init__(self) -> None:
        self.item, self.versions = active_item()
        self.idempotency = {}
        self.audits: list[dict] = []
        self.hard_deleted = False

    async def get_idempotent(self, tenant_id, operation, request_id):
        return self.idempotency.get((tenant_id, operation, request_id))

    async def put_idempotent(self, tenant_id, operation, request_id, response):
        self.idempotency[(tenant_id, operation, request_id)] = response

    async def get_item(self, memory_id, **_):
        if self.hard_deleted or memory_id != self.item.memory_id:
            return None
        return deepcopy(self.item)

    async def set_item_status(self, _item, target):
        self.item.status = target
        self.item.deleted_at = NOW if target is MemoryStatus.DELETED else None
        return deepcopy(self.item)

    async def append_audit(self, **event):
        self.audits.append(event)

    async def list_user_items(self, **_):
        return [deepcopy(self.item)]

    async def list_user_versions(self, **_):
        return deepcopy(self.versions)

    async def list_audit_events(self, **_):
        return [
            {
                "action": event["action"].value,
                "details": event["details"],
            }
            for event in self.audits
        ]

    async def hard_delete_item(self, _item):
        self.hard_deleted = True
        return {
            "deleted_versions": len(self.versions),
            "deleted_candidates": 1,
        }


class Uow:
    def __init__(self, repository):
        self.repository = repository

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None


class UowFactory:
    def __init__(self, repository):
        self.repository = repository

    def __call__(self):
        return Uow(self.repository)


def service_for(repository, *, hard_delete_enabled=False) -> MemoryService:
    return MemoryService(
        UowFactory(repository),  # type: ignore[arg-type]
        hard_delete_enabled=hard_delete_enabled,
    )


@pytest.mark.asyncio
async def test_jsonl_export_contains_all_versions_provenance_and_audit() -> None:
    repository = LifecycleRepository()
    exported = await service_for(repository).export_user(
        "user-a",
        tenant_id="tenant-a",
        format="jsonl",
        request_id="export-1",
    )

    assert [entry.version_no for entry in exported.versions] == [1, 2]
    assert exported.versions[1].provenance["source_turn_id"] == "turn-2"
    assert exported.audit_events[-1]["action"] == "export"
    records = [json.loads(line) for line in render_memory_export_jsonl(exported).splitlines()]
    assert [record["record_type"] for record in records] == [
        "metadata",
        "memory_item",
        "memory_version",
        "memory_version",
        "audit_event",
    ]


@pytest.mark.asyncio
async def test_soft_delete_hides_item_and_restore_makes_it_active() -> None:
    repository = LifecycleRepository()
    service = service_for(repository)
    actor = MemoryActor(actor_type=ActorType.USER, actor_id="user-a")

    await service.delete(
        repository.item.memory_id,
        tenant_id="tenant-a",
        user_id="user-a",
        reason="user requested",
        actor=actor,
        request_id="delete-1",
    )
    assert repository.item.status is MemoryStatus.DELETED

    restored = await service.restore(
        repository.item.memory_id,
        tenant_id="tenant-a",
        user_id="user-a",
        actor=actor,
        request_id="restore-1",
    )
    assert restored.status is MemoryStatus.ACTIVE
    assert [event["action"].value for event in repository.audits] == [
        "delete",
        "restore",
    ]


@pytest.mark.asyncio
async def test_hard_delete_requires_flag_admin_confirmation_and_prior_soft_delete() -> None:
    repository = LifecycleRepository()
    memory_id = repository.item.memory_id
    admin = MemoryActor(actor_type=ActorType.ADMIN, actor_id="admin-a")
    arguments = {
        "tenant_id": "tenant-a",
        "user_id": "user-a",
        "reason": "approved erasure",
        "confirmation": f"HARD_DELETE:{memory_id}",
        "actor": admin,
        "request_id": "hard-delete-1",
    }

    with pytest.raises(MemoryAuthorizationError):
        await service_for(repository).hard_delete(memory_id, **arguments)

    enabled = service_for(repository, hard_delete_enabled=True)
    with pytest.raises(MemoryStateError):
        await enabled.hard_delete(memory_id, **arguments)

    repository.item.status = MemoryStatus.DELETED
    with pytest.raises(MemoryAuthorizationError):
        await enabled.hard_delete(
            memory_id,
            **(arguments | {"confirmation": "wrong"}),
        )

    result = await enabled.hard_delete(memory_id, **arguments)
    assert result.deleted_versions == 2
    assert result.deleted_candidates == 1
    assert result.audit_retained is True
    assert repository.hard_deleted is True
    assert repository.audits[-1]["action"].value == "hard_delete"
