"""Meguri core runtime package."""

__all__ = ["app"]


def __getattr__(name: str):
    """Keep package imports side-effect free for Alembic and worker processes."""

    if name == "app":
        from .app import app

        return app
    raise AttributeError(name)
