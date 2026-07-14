"""Fail-closed loading for Docker/host-mounted secret files."""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path


class SecretConfigurationError(RuntimeError):
    """A secret is missing, ambiguous, inline, or unreadable."""


def read_secret(
    values: Mapping[str, str],
    variable: str,
    *,
    required: bool = True,
) -> str | None:
    """Read ``<variable>_FILE`` without ever accepting an inline value."""

    file_variable = f"{variable}_FILE"
    inline = values.get(variable, "").strip()
    file_name = values.get(file_variable, "").strip()
    if inline:
        raise SecretConfigurationError(f"{variable} must not be supplied inline; use {file_variable}")
    if not file_name:
        if required:
            raise SecretConfigurationError(f"{file_variable} is required")
        return None
    try:
        value = Path(file_name).read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise SecretConfigurationError(f"{file_variable} is unreadable") from exc
    if not value:
        raise SecretConfigurationError(f"{file_variable} points to an empty file")
    return value
