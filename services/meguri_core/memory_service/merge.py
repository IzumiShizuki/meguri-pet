from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Any
from uuid import UUID

from .enums import MergePolicy


@dataclass(frozen=True)
class MergeResult:
    content_json: dict[str, Any]
    conflicted_fields: frozenset[str] = frozenset()

    @property
    def conflicted(self) -> bool:
        return bool(self.conflicted_fields)


def is_ancestor_version(
    base_version_id: UUID,
    current_version_id: UUID,
    supersedes_by_version: dict[UUID, UUID | None],
) -> bool:
    """Return whether base is on the immutable stable-version parent chain."""
    seen: set[UUID] = set()
    cursor: UUID | None = current_version_id
    while cursor is not None and cursor not in seen:
        if cursor == base_version_id:
            return True
        seen.add(cursor)
        cursor = supersedes_by_version.get(cursor)
    return False


_MISSING = object()


def _changed(base: dict[str, Any], value: dict[str, Any]) -> set[str]:
    return {
        key
        for key in base.keys() | value.keys()
        if base.get(key, _MISSING) != value.get(key, _MISSING)
    }


def _json_identity(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _deduplicate(values: list[Any]) -> list[Any]:
    seen: set[str] = set()
    result = []
    for value in values:
        identity = _json_identity(value)
        if identity not in seen:
            seen.add(identity)
            result.append(value)
    return result


class MemoryMergeEngine:
    """Deterministic merge policies for already-approved memory candidates."""

    def merge(
        self,
        policy: MergePolicy,
        *,
        current: dict[str, Any],
        proposed: dict[str, Any],
        base: dict[str, Any] | None = None,
    ) -> MergeResult:
        if policy in {MergePolicy.REPLACE, MergePolicy.STATE_TRANSITION, MergePolicy.SUPERSEDE}:
            return MergeResult(dict(proposed))
        if policy is MergePolicy.CONFIRM:
            conflicts = frozenset(
                _changed(current, proposed) | _changed(proposed, current)
            )
            return MergeResult(dict(current), conflicts)
        if policy in {MergePolicy.SET_UNION, MergePolicy.SET_REMOVE}:
            return MergeResult(self._merge_sets(policy, current, proposed))
        if policy is MergePolicy.THREE_WAY_MERGE:
            if base is None:
                raise ValueError("three_way_merge requires a common base version")
            local_changes = _changed(base, proposed)
            server_changes = _changed(base, current)
            conflicts = frozenset(
                key for key in local_changes & server_changes
                if proposed.get(key, _MISSING) != current.get(key, _MISSING)
            )
            if conflicts:
                return MergeResult(dict(current), conflicts)
            merged = dict(base)
            merged.update(current)
            for key in local_changes:
                if key in proposed:
                    merged[key] = proposed[key]
                else:
                    merged.pop(key, None)
            return MergeResult(merged)
        if policy is MergePolicy.MANUAL:
            return MergeResult(dict(current), frozenset(current.keys() | proposed.keys()) or {"value"})
        if policy in {MergePolicy.REVIEW, MergePolicy.CREATE_ONLY}:
            return MergeResult(dict(proposed))
        raise ValueError(f"unsupported merge policy: {policy}")

    @staticmethod
    def _merge_sets(
        policy: MergePolicy,
        current: dict[str, Any],
        proposed: dict[str, Any],
    ) -> dict[str, Any]:
        merged = dict(current)
        for key, value in proposed.items():
            if not isinstance(value, list):
                raise ValueError(f"{policy.value} requires list values: {key}")
            existing = merged.get(key, [])
            if not isinstance(existing, list):
                raise ValueError(f"{policy.value} requires an existing list: {key}")
            if policy is MergePolicy.SET_UNION:
                merged[key] = _deduplicate([*existing, *value])
            else:
                removals = {_json_identity(entry) for entry in value}
                merged[key] = [
                    entry for entry in existing if _json_identity(entry) not in removals
                ]
        return merged
