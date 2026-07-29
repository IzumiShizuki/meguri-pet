from __future__ import annotations

import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest

from services.meguri_core.memory_service.enums import MemoryStatus, MergePolicy
from services.meguri_core.memory_service.file_mirror import (
    FileMirrorProjector,
    MemoryProjectionRepairService,
    MirrorScanner,
    _atomic_write,
    _safe_projection_provenance,
)
from services.meguri_core.memory_service.merge import (
    MemoryMergeEngine,
    is_ancestor_version,
)


def test_all_declared_merge_policies_have_deterministic_semantics():
    engine = MemoryMergeEngine()
    current = {"name": "Meguri", "tags": ["tea", "music"], "timezone": "Asia/Shanghai"}
    assert engine.merge(
        MergePolicy.REPLACE, current=current, proposed={"name": "Meguri-chan"}
    ).content_json == {"name": "Meguri-chan"}
    assert engine.merge(
        MergePolicy.SET_UNION, current=current, proposed={"tags": ["music", "books"]}
    ).content_json["tags"] == ["tea", "music", "books"]
    assert engine.merge(
        MergePolicy.SET_REMOVE, current=current, proposed={"tags": ["tea"]}
    ).content_json["tags"] == ["music"]
    object_set = engine.merge(
        MergePolicy.SET_UNION,
        current={"people": [{"id": 1}]},
        proposed={"people": [{"id": 1}, {"id": 2}]},
    )
    assert object_set.content_json["people"] == [{"id": 1}, {"id": 2}]
    confirmed = engine.merge(
        MergePolicy.CONFIRM, current=current, proposed=current
    )
    assert not confirmed.conflicted
    mismatched_confirmation = engine.merge(
        MergePolicy.CONFIRM, current=current, proposed={"name": "ignored"}
    )
    assert mismatched_confirmation.content_json == current
    assert mismatched_confirmation.conflicted
    assert engine.merge(
        MergePolicy.STATE_TRANSITION, current=current, proposed={"state": "done"}
    ).content_json == {"state": "done"}


def test_three_way_merge_only_accepts_non_overlapping_changes():
    engine = MemoryMergeEngine()
    base = {"name": "Meguri", "timezone": "UTC", "language": "zh"}
    merged = engine.merge(
        MergePolicy.THREE_WAY_MERGE,
        base=base,
        current={**base, "timezone": "Asia/Shanghai"},
        proposed={**base, "name": "Meguri-chan"},
    )
    assert not merged.conflicted
    assert merged.content_json == {
        "name": "Meguri-chan", "timezone": "Asia/Shanghai", "language": "zh"
    }
    removed = engine.merge(
        MergePolicy.THREE_WAY_MERGE,
        base=base,
        current={**base, "timezone": "Asia/Shanghai"},
        proposed={"name": "Meguri", "timezone": "UTC"},
    )
    assert removed.content_json == {"name": "Meguri", "timezone": "Asia/Shanghai"}
    conflict = engine.merge(
        MergePolicy.THREE_WAY_MERGE,
        base=base,
        current={**base, "name": "A"},
        proposed={**base, "name": "B"},
    )
    assert conflict.conflicted_fields == frozenset({"name"})
    assert engine.merge(
        MergePolicy.MANUAL, current=base, proposed={"name": "B"}
    ).conflicted


def test_common_base_must_be_on_current_stable_chain():
    root, middle, current, conflict = uuid4(), uuid4(), uuid4(), uuid4()
    parents = {root: None, middle: root, current: middle, conflict: root}
    assert is_ancestor_version(root, current, parents)
    assert is_ancestor_version(middle, current, parents)
    assert not is_ancestor_version(conflict, current, parents)

    cycle_a, cycle_b = uuid4(), uuid4()
    assert not is_ancestor_version(root, cycle_a, {cycle_a: cycle_b, cycle_b: cycle_a})


def test_set_merge_rejects_unstructured_values():
    with pytest.raises(ValueError, match="requires list values"):
        MemoryMergeEngine().merge(
            MergePolicy.SET_UNION, current={}, proposed={"tags": "tea"}
        )


def test_atomic_mirror_and_incremental_scanner_use_mtime_size_then_hash(tmp_path: Path):
    root = tmp_path / "memories"
    item = root / "items" / "memory-a.json"
    document = {
        "memory_id": "memory-a", "base_version_id": "version-a",
        "content_text": "User likes tea", "content_json": {"drink": "tea"},
    }
    encoded = (json.dumps(document, sort_keys=True) + "\n").encode()
    _atomic_write(item, encoded)
    stat = item.stat()
    state = {
        "files": {
            "items/memory-a.json": {
                "mtime_ns": stat.st_mtime_ns, "size": stat.st_size,
                "sha256": hashlib.sha256(encoded).hexdigest(),
            }
        }
    }
    _atomic_write(root / "sync-state.json", json.dumps(state).encode())
    assert MirrorScanner(root).scan() == []

    changed = {**document, "content_text": "User likes coffee"}
    _atomic_write(item, json.dumps(changed).encode())
    changes = MirrorScanner(root).scan()
    assert len(changes) == 1
    assert changes[0].path == item
    assert changes[0].sha256 == hashlib.sha256(item.read_bytes()).hexdigest()
    assert not list(item.parent.glob("*.tmp"))


def test_file_projection_keeps_trace_ids_but_never_arbitrary_raw_provenance():
    assert _safe_projection_provenance(
        {
            "source_turn_id": "turn-1",
            "raw_excerpt": "must not reach the local mirror",
            "nested_payload": {"secret": "must not reach the local mirror"},
        }
    ) == {"source_turn_id": "turn-1"}


@pytest.mark.asyncio
async def test_conflict_notification_generates_machine_and_user_views(tmp_path: Path):
    memory_id = uuid4()
    branch_id = uuid4()
    projector = FileMirrorProjector(None, tmp_path, worker_id="mirror")  # type: ignore[arg-type]
    await projector._project(
        SimpleNamespace(
            event_type="conflict.notification",
            payload={
                "memory_id": str(memory_id),
                "branch_version_id": str(branch_id),
                "last_stable_version_id": str(uuid4()),
            },
        )
    )
    assert (tmp_path / "conflicts" / f"{memory_id}-{branch_id}.json").exists()
    assert str(branch_id) in (tmp_path / "views" / "pending-memory.md").read_text()


class MirrorWorkerUow:
    def __init__(self, repository):
        self.repository = repository

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None


class MirrorWorkerUowFactory:
    def __init__(self, repository):
        self.repository = repository

    def __call__(self):
        return MirrorWorkerUow(self.repository)


@pytest.mark.asyncio
async def test_repair_reprojects_database_version_without_reading_local_content(tmp_path: Path):
    memory_id, version_id = uuid4(), uuid4()
    repository = SimpleNamespace()

    async def get_item_owner(*_, **__):
        return "user-1"

    async def get_item(*_, **__):
        return SimpleNamespace(current_version_id=version_id)

    repository.get_item_owner = get_item_owner
    repository.get_item = get_item
    projector = FileMirrorProjector(
        MirrorWorkerUowFactory(repository),  # type: ignore[arg-type]
        tmp_path,
        worker_id="mirror-repair",
    )
    projected = []

    async def project(task):
        projected.append(task)

    projector._project = project  # type: ignore[method-assign]
    result = await projector.repair(tenant_id="tenant-1", memory_ids=[memory_id])

    assert result == {"requested": 1, "repaired": 1, "missing": 0, "failed": 0}
    assert projected[0].payload == {
        "tenant_id": "tenant-1",
        "memory_id": str(memory_id),
        "version_id": str(version_id),
    }


def authoritative_item(memory_id, version_id, *, content="current", status=MemoryStatus.ACTIVE):
    return SimpleNamespace(
        memory_id=memory_id,
        current_version_id=version_id,
        last_stable_version_id=version_id,
        memory_type="user_preference",
        status=status,
        current_version=SimpleNamespace(
            version_no=2,
            status="active",
            base_version_id=uuid4(),
            content_text=content,
            content_json={"value": content},
            created_at=SimpleNamespace(isoformat=lambda: "2026-07-29T00:00:00+00:00"),
            provenance={"source_turn_id": "turn-current"},
        ),
    )


class AuthoritativeMirrorRepository:
    def __init__(self, item):
        self.item = item
        self.for_update = False
        self.deleted_embeddings = []

    async def get_item_owner(self, *_args, **_kwargs):
        return "user-1"

    async def get_item(self, *_args, **kwargs):
        self.for_update = kwargs.get("for_update", False)
        return self.item

    async def delete_embedding_projection(self, version_id):
        self.deleted_embeddings.append(version_id)


@pytest.mark.asyncio
@pytest.mark.parametrize("event_type", ["file_mirror.requested", "file_mirror.deleted"])
async def test_late_file_mirror_events_project_authoritative_current_version(
    tmp_path: Path, event_type: str
):
    memory_id, old_version_id, current_version_id = uuid4(), uuid4(), uuid4()
    repository = AuthoritativeMirrorRepository(
        authoritative_item(memory_id, current_version_id)
    )
    projector = FileMirrorProjector(
        MirrorWorkerUowFactory(repository),  # type: ignore[arg-type]
        tmp_path,
        worker_id="mirror",
    )
    payload = {
        "tenant_id": "tenant-1",
        "memory_id": str(memory_id),
        "version_id": str(old_version_id),
        "deleted_version_id": str(old_version_id),
    }

    await projector._project(SimpleNamespace(event_type=event_type, payload=payload))

    document = json.loads((tmp_path / "items" / f"{memory_id}.json").read_text())
    assert document["current_version_id"] == str(current_version_id)
    assert document["content_text"] == "current"
    assert repository.for_update


@pytest.mark.asyncio
async def test_current_database_tombstone_removes_projection_for_late_write_event(tmp_path: Path):
    memory_id, tombstone_id, old_version_id = uuid4(), uuid4(), uuid4()
    item_path = tmp_path / "items" / f"{memory_id}.json"
    _atomic_write(item_path, b"{}\n")
    repository = AuthoritativeMirrorRepository(
        authoritative_item(memory_id, tombstone_id, status=MemoryStatus.DELETED)
    )
    projector = FileMirrorProjector(
        MirrorWorkerUowFactory(repository),  # type: ignore[arg-type]
        tmp_path,
        worker_id="mirror",
    )

    await projector._project(
        SimpleNamespace(
            event_type="file_mirror.requested",
            payload={
                "tenant_id": "tenant-1",
                "memory_id": str(memory_id),
                "version_id": str(old_version_id),
            },
        )
    )

    assert not item_path.exists()
    assert repository.for_update


@pytest.mark.asyncio
async def test_file_mirror_dead_letter_requeue_is_scoped_to_mirror_events():
    captured = []
    repository = SimpleNamespace()

    async def requeue_dead_letters(**kwargs):
        captured.append(kwargs["event_types"])
        return 3

    repository.requeue_dead_letters = requeue_dead_letters
    service = MemoryProjectionRepairService(
        MirrorWorkerUowFactory(repository)  # type: ignore[arg-type]
    )

    assert await service.requeue_file_mirror_dead_letters() == 3
    assert "file_mirror.requested" in captured[0]
    assert "embedding.requested" not in captured[0]
