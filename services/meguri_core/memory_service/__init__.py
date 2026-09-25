"""Native Memory service package with side-effect-free module discovery."""

from __future__ import annotations

from importlib import import_module

__all__ = [
    "AuthoritativeMemoryProvider",
    "EmbeddingProvider",
    "MemoryAuthorizationError",
    "MemoryConflictError",
    "MemoryNotFoundError",
    "MemoryServiceError",
    "MemoryStateError",
    "MemoryUnavailableError",
]


def __getattr__(name: str):
    for module_name in ("contracts", "enums", "models"):
        module = import_module(f"{__name__}.{module_name}")
        if hasattr(module, name):
            return getattr(module, name)
    raise AttributeError(name)
