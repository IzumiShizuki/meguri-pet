from contextlib import redirect_stdout
import io
from pathlib import Path

from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory


ROOT = Path(__file__).resolve().parents[3]


def alembic_config() -> Config:
    return Config(str(ROOT / "alembic.ini"))


def render_upgrade_sql() -> str:
    output = io.StringIO()
    with redirect_stdout(output):
        command.upgrade(alembic_config(), "head", sql=True)
    return output.getvalue()


def render_downgrade_sql() -> str:
    output = io.StringIO()
    with redirect_stdout(output):
        command.downgrade(alembic_config(), "head:base", sql=True)
    return output.getvalue()


def test_revision_chain_is_linear_and_complete():
    scripts = ScriptDirectory.from_config(alembic_config())
    assert scripts.get_current_head() == "20260714_0004"
    assert [revision.revision for revision in scripts.walk_revisions()] == [
        "20260714_0004",
        "20260714_0003",
        "20260714_0002",
        "20260714_0001",
    ]


def test_offline_upgrade_contains_required_schema_and_no_hnsw():
    sql = render_upgrade_sql().lower()
    assert "create extension if not exists vector" in sql
    for table in (
        "identity_bindings",
        "memory_candidates",
        "memory_items",
        "memory_versions",
        "memory_embeddings",
        "memory_feedback",
        "session_summaries",
        "memory_audit_log",
        "memory_outbox",
    ):
        assert f"create table {table}" in sql
    assert "memory_versions are immutable" in sql
    assert "memory_audit_log is append-only" in sql
    assert "using hnsw" not in sql


def test_offline_downgrade_removes_memory_schema_and_extension():
    sql = render_downgrade_sql().lower()
    assert "drop table memory_outbox" in sql
    assert "drop table memory_items" in sql
    assert "drop extension if exists vector" in sql
