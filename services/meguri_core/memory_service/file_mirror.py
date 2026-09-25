from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Iterable
from uuid import UUID, uuid4

from .enums import MemoryStatus
from .repository import MemoryUnitOfWorkFactory


def _canonical_json(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def _atomic_write(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.{uuid4().hex}.tmp")
    try:
        with temporary.open("wb") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
    _fsync_directory(path.parent)


def _fsync_directory(path: Path) -> None:
    try:
        directory = os.open(path, os.O_RDONLY)
    except (AttributeError, OSError):
        return
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def _durable_unlink(path: Path) -> None:
    path.unlink(missing_ok=True)
    _fsync_directory(path.parent)


_PROJECTION_PROVENANCE_KEYS = frozenset(
    {
        "candidate_id",
        "change_reason",
        "restored_from_version_id",
        "source_client_id",
        "source_kind",
        "source_message_ids",
        "source_record_id",
        "source_session_id",
        "source_system",
        "source_turn_id",
    }
)


def _safe_projection_provenance(provenance: dict[str, Any]) -> dict[str, Any]:
    """Keep trace identifiers while excluding arbitrary source payloads from disk."""
    return {
        key: value
        for key, value in provenance.items()
        if key in _PROJECTION_PROVENANCE_KEYS
        and isinstance(value, (str, int, float, bool, type(None), list))
    }


@dataclass(frozen=True)
class MirrorChange:
    path: Path
    sha256: str


class MirrorScanner:
    """Detect projection drift only; local documents are never an ingestion source."""

    def __init__(self, root: Path) -> None:
        self.root = root

    def scan(self) -> list[MirrorChange]:
        state = self._load_state()
        changes: list[MirrorChange] = []
        for path in sorted((self.root / "items").glob("*.json")):
            stat = path.stat()
            key = path.relative_to(self.root).as_posix()
            previous = state.get("files", {}).get(key, {})
            if previous.get("mtime_ns") == stat.st_mtime_ns and previous.get("size") == stat.st_size:
                continue
            raw = path.read_bytes()
            digest = hashlib.sha256(raw).hexdigest()
            if previous.get("sha256") == digest:
                continue
            document = json.loads(raw)
            required = {"memory_id", "base_version_id", "content_text", "content_json"}
            if not isinstance(document, dict) or not required <= document.keys():
                raise ValueError(f"invalid memory mirror schema: {path}")
            changes.append(MirrorChange(path, digest))
        return changes

    def _load_state(self) -> dict[str, Any]:
        path = self.root / "sync-state.json"
        if not path.exists():
            return {"files": {}}
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {"files": {}}


class FileMirrorProjector:
    def __init__(
        self, uow_factory: MemoryUnitOfWorkFactory, root: Path, *, worker_id: str,
        batch_size: int = 20, max_attempts: int = 5,
    ) -> None:
        self.uow_factory = uow_factory
        self.root = root
        self.worker_id = worker_id
        self.batch_size = batch_size
        self.max_attempts = max_attempts

    @staticmethod
    def _repository(uow):
        if uow.repository is None:
            raise RuntimeError("memory unit of work is not active")
        return uow.repository

    async def run_once(self) -> dict[str, int]:
        async with self.uow_factory() as uow:
            tasks = await self._repository(uow).claim_outbox(
                worker_id=self.worker_id, limit=self.batch_size, lease_seconds=300,
                event_types=(
                    "file_mirror.requested", "file_mirror.deleted",
                    "projection.deleted", "conflict.notification"
                ),
            )
        completed = failed = 0
        for task in tasks:
            try:
                await self._project(task)
                async with self.uow_factory() as uow:
                    acknowledged = await self._repository(uow).complete_outbox(
                        task.outbox_id,
                        worker_id=self.worker_id,
                        claim_locked_at=task.locked_at,
                    )
                completed += int(acknowledged)
                failed += int(not acknowledged)
            except Exception as exc:
                async with self.uow_factory() as uow:
                    await self._repository(uow).fail_outbox(
                        task.outbox_id,
                        worker_id=self.worker_id,
                        claim_locked_at=task.locked_at,
                        error_code=type(exc).__name__,
                        max_attempts=self.max_attempts,
                        retry_delay_seconds=30 * 2 ** min(task.attempts, 8),
                    )
                failed += 1
        return {"claimed": len(tasks), "completed": completed, "failed": failed}

    async def repair(self, *, tenant_id: str, memory_ids: Iterable[UUID]) -> dict[str, int]:
        """Rebuild selected local projections from the authoritative database."""
        repaired = missing = failed = 0
        for memory_id in memory_ids:
            try:
                async with self.uow_factory() as uow:
                    repository = self._repository(uow)
                    owner = await repository.get_item_owner(memory_id, tenant_id=tenant_id)
                    item = (
                        await repository.get_item(
                            memory_id, tenant_id=tenant_id, user_id=owner
                        )
                        if owner
                        else None
                    )
                if item is None:
                    missing += 1
                    continue
                await self._project(
                    SimpleNamespace(
                        event_type="file_mirror.requested",
                        payload={
                            "tenant_id": tenant_id,
                            "memory_id": str(memory_id),
                            "version_id": str(item.current_version_id),
                        },
                    )
                )
                repaired += 1
            except Exception:
                failed += 1
        return {
            "requested": repaired + missing + failed,
            "repaired": repaired,
            "missing": missing,
            "failed": failed,
        }

    async def _project(self, task) -> None:
        memory_id = UUID(str(task.payload["memory_id"]))
        if task.event_type == "conflict.notification":
            branch_id = UUID(str(task.payload["branch_version_id"]))
            conflict = self.root / "conflicts" / f"{memory_id}-{branch_id}.json"
            await asyncio.to_thread(_atomic_write, conflict, _canonical_json(task.payload))
            pending = sorted((self.root / "conflicts").glob("*.json"))
            markdown = "# Pending memory conflicts\n\n" + "".join(
                f"- `{path.stem}`\n" for path in pending
            )
            await asyncio.to_thread(
                _atomic_write, self.root / "views" / "pending-memory.md", markdown.encode("utf-8")
            )
            return
        tenant_id = str(task.payload["tenant_id"])
        async with self.uow_factory() as uow:
            repository = self._repository(uow)
            if task.event_type == "projection.deleted":
                await repository.delete_embedding_projection(
                    UUID(str(task.payload["deleted_version_id"]))
                )
            owner = await repository.get_item_owner(memory_id, tenant_id=tenant_id)
            item = (
                await repository.get_item(
                    memory_id,
                    tenant_id=tenant_id,
                    user_id=owner,
                    for_update=True,
                )
                if owner
                else None
            )
            item_path = self.root / "items" / f"{memory_id}.json"
            if item is None or item.status == MemoryStatus.DELETED:
                await asyncio.to_thread(_durable_unlink, item_path)
                await asyncio.to_thread(self._write_state, item_path)
                return

            # Keep the database row locked through the atomic replace so an older
            # event cannot land after a concurrent update or restore.
            version = item.current_version
            version_id = item.current_version_id
            version_document = {
                "memory_id": str(memory_id), "version_id": str(version_id),
                "version_no": version.version_no, "status": version.status,
                "base_version_id": (
                    str(version.base_version_id) if version.base_version_id else None
                ),
                "content_text": version.content_text, "content_json": version.content_json,
                "created_at": version.created_at.isoformat(),
                "provenance": _safe_projection_provenance(version.provenance),
            }
            item_document = {
                "memory_id": str(memory_id), "base_version_id": str(version_id),
                "current_version_id": str(version_id),
                "last_stable_version_id": (
                    str(item.last_stable_version_id) if item.last_stable_version_id else None
                ),
                "memory_type": item.memory_type, "status": item.status,
                "content_text": version.content_text, "content_json": version.content_json,
            }
            await asyncio.to_thread(
                _atomic_write,
                self.root / "versions" / str(memory_id) / f"{version.version_no}.json",
                _canonical_json(version_document),
            )
            await asyncio.to_thread(_atomic_write, item_path, _canonical_json(item_document))
            await asyncio.to_thread(self._write_state, item_path)

    def _write_state(self, changed: Path | None) -> None:
        scanner = MirrorScanner(self.root)
        state = scanner._load_state()
        state["verified_server_version_at"] = datetime.now(timezone.utc).isoformat()
        files = state.setdefault("files", {})
        if changed is not None:
            key = changed.relative_to(self.root).as_posix()
            if not changed.exists():
                files.pop(key, None)
            else:
                raw = changed.read_bytes()
                stat = changed.stat()
                files[key] = {
                "mtime_ns": stat.st_mtime_ns, "size": stat.st_size,
                "sha256": hashlib.sha256(raw).hexdigest(),
                }
        _atomic_write(self.root / "sync-state.json", _canonical_json(state))


class MemoryProjectionRepairService:
    def __init__(self, uow_factory: MemoryUnitOfWorkFactory) -> None:
        self.uow_factory = uow_factory

    async def requeue_dead_letters(self) -> int:
        async with self.uow_factory() as uow:
            if uow.repository is None:
                raise RuntimeError("memory unit of work is not active")
            return await uow.repository.requeue_dead_letters(
                event_types=(
                    "embedding.requested", "file_mirror.requested",
                    "embedding.deleted", "file_mirror.deleted",
                    "projection.deleted", "conflict.notification",
                )
            )

    async def requeue_file_mirror_dead_letters(self) -> int:
        async with self.uow_factory() as uow:
            if uow.repository is None:
                raise RuntimeError("memory unit of work is not active")
            return await uow.repository.requeue_dead_letters(
                event_types=(
                    "file_mirror.requested",
                    "file_mirror.deleted",
                    "projection.deleted",
                    "conflict.notification",
                )
            )
