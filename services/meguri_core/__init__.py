"""Meguri core runtime package.

Keep the FastAPI application lazy so contract-only consumers (dataset,
training, and evaluation tools) can import ``services.meguri_core.schemas``
without pulling the web serving dependency into the training environment.
"""

from typing import Any

__all__ = ["app"]


def __getattr__(name: str) -> Any:
    if name == "app":
        from .app import app

        return app
    raise AttributeError(name)
