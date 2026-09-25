"""Loopback-only ModelScope Skill download bridge.

The bridge downloads public packages into a unique staging directory. Java Core
remains authoritative for package validation, digests, catalog state, and audit.
"""

from __future__ import annotations

import asyncio
import inspect
import os
import re
import shutil
from pathlib import Path
from threading import Event
from typing import Any, Callable
from uuid import uuid4

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from .memory_bridge import _authorize


router = APIRouter(prefix="/internal/skills/modelscope", tags=["internal-skill-bridge"])

MAX_SKILL_FILES = 256
MAX_SKILL_FILE_BYTES = 1_048_576
MAX_SKILL_PACKAGE_BYTES = 8_388_608
PUBLIC_SKILL_ID = re.compile(
    r"^@?[A-Za-z0-9][A-Za-z0-9._-]{0,127}/[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"
)


class SkillFetchRequest(BaseModel):
    skill_id: str = Field(min_length=1, max_length=255)


class SkillReleaseRequest(BaseModel):
    fetch_id: str = Field(pattern=r"^[0-9a-f]{32}$")


def _staging_root() -> Path:
    configured = os.getenv("MEGURI_SKILL_STAGING_ROOT", "").strip()
    root = (
        Path(configured)
        if configured
        else Path.home() / ".meguri" / "skills" / "staging"
    )
    root = root.expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    return root


def _download_timeout_seconds() -> float:
    configured = os.getenv("MEGURI_MODELSCOPE_DOWNLOAD_TIMEOUT_SECONDS", "12").strip()
    try:
        value = float(configured)
    except ValueError:
        value = 12.0
    return min(300.0, max(0.1, value))


def _download_public_skill(skill_id: str, destination: Path) -> Any:
    try:
        from modelscope_hub import HubApi
    except ImportError as exc:
        raise HTTPException(
            status_code=503, detail="modelscope-hub is unavailable"
        ) from exc
    # Explicit empty token prevents the SDK from inheriting environment or
    # persisted credentials, so this bridge cannot become a private catalog path.
    api = HubApi(endpoint="https://modelscope.cn", token="")
    download_skill: Callable[..., Any] | None = getattr(api, "download_skill", None)
    if callable(download_skill):
        parameters = inspect.signature(download_skill).parameters
        if "local_dir" in parameters:
            return download_skill(skill_id, local_dir=str(destination))
        return download_skill(skill_id, str(destination))
    download_repo: Callable[..., Any] | None = getattr(api, "download_repo", None)
    if callable(download_repo):
        return download_repo(
            skill_id,
            repo_type="skill",
            local_dir=str(destination),
            max_workers=4,
        )
    raise HTTPException(
        status_code=503, detail="modelscope-hub skill API is unavailable"
    )


def _validate_staging_limits(root: Path) -> None:
    files = 0
    package_bytes = 0
    for entry in root.rglob("*"):
        if entry.is_symlink():
            raise HTTPException(
                status_code=502, detail="download produced a symbolic link"
            )
        if not entry.is_file():
            continue
        files += 1
        size = entry.stat().st_size
        package_bytes += size
        if files > MAX_SKILL_FILES:
            raise HTTPException(
                status_code=413, detail="download contains too many files"
            )
        if size > MAX_SKILL_FILE_BYTES:
            raise HTTPException(status_code=413, detail="download file exceeds limit")
        if package_bytes > MAX_SKILL_PACKAGE_BYTES:
            raise HTTPException(
                status_code=413, detail="download package exceeds limit"
            )


def _cleanup_staging(destination: Path, staging_root: Path) -> None:
    resolved = destination.resolve()
    if resolved.parent != staging_root or not resolved.name.startswith("fetch-"):
        return
    shutil.rmtree(resolved, ignore_errors=True)


async def _download_bounded(
    skill_id: str,
    destination: Path,
    staging_root: Path,
) -> Any:
    cleanup_requested = Event()
    worker_done = Event()

    def run_download() -> Any:
        try:
            return _download_public_skill(skill_id, destination)
        finally:
            worker_done.set()
            if cleanup_requested.is_set():
                _cleanup_staging(destination, staging_root)

    task = asyncio.create_task(asyncio.to_thread(run_download))
    try:
        return await asyncio.wait_for(
            asyncio.shield(task), timeout=_download_timeout_seconds()
        )
    except (TimeoutError, asyncio.CancelledError) as exc:
        # Python cannot force-stop a worker thread safely. The event handshake
        # makes the worker clean its own directory only after the SDK call has
        # returned, including when the event loop itself is shutting down.
        cleanup_requested.set()
        if worker_done.is_set():
            _cleanup_staging(destination, staging_root)
        if isinstance(exc, asyncio.CancelledError):
            raise
        raise HTTPException(
            status_code=504, detail="public skill download timed out"
        ) from exc


@router.get("/health")
async def skill_bridge_health(request: Request) -> dict[str, str]:
    await _authorize(request)
    try:
        import modelscope_hub  # noqa: F401
    except ImportError:
        raise HTTPException(
            status_code=503, detail="modelscope-hub is unavailable"
        ) from None
    return {"status": "ok", "provider": "modelscope-hub"}


@router.post("/fetch")
async def fetch_public_skill(
    body: SkillFetchRequest, request: Request
) -> dict[str, str]:
    await _authorize(request)
    if not PUBLIC_SKILL_ID.fullmatch(body.skill_id):
        raise HTTPException(status_code=400, detail="invalid public skill id")
    staging_root = _staging_root()
    fetch_id = uuid4().hex
    destination = (staging_root / f"fetch-{fetch_id}").resolve()
    if destination.parent != staging_root:
        raise HTTPException(status_code=400, detail="invalid staging path")
    destination.mkdir(parents=False, exist_ok=False)
    try:
        result = await _download_bounded(body.skill_id, destination, staging_root)
    except HTTPException as exc:
        if exc.status_code != 504:
            _cleanup_staging(destination, staging_root)
        raise
    except Exception as exc:
        _cleanup_staging(destination, staging_root)
        raise HTTPException(
            status_code=502, detail="public skill download failed"
        ) from exc
    try:
        result_path = (
            Path(result).resolve()
            if isinstance(result, (str, os.PathLike))
            else destination
        )
        if result_path != destination and destination not in result_path.parents:
            raise HTTPException(status_code=502, detail="download escaped staging root")
        if not result_path.is_dir():
            raise HTTPException(
                status_code=502, detail="download did not produce a directory"
            )
        _validate_staging_limits(result_path)
    except Exception:
        _cleanup_staging(destination, staging_root)
        raise
    return {
        "skill_id": body.skill_id,
        "fetch_id": fetch_id,
        "staging_path": str(result_path),
        "revision": "",
    }


@router.post("/release")
async def release_public_skill(
    body: SkillReleaseRequest, request: Request
) -> dict[str, str]:
    await _authorize(request)
    staging_root = _staging_root()
    destination = staging_root / f"fetch-{body.fetch_id}"
    _cleanup_staging(destination, staging_root)
    return {"fetch_id": body.fetch_id, "status": "released"}
