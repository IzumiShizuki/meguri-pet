from pathlib import Path
from uuid import uuid4

import pytest
import yaml

from scripts.run_memory_file_mirror_worker import parse_args, resolve_mirror_root


def test_mirror_root_prefers_cli_and_falls_back_to_environment(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
):
    environment_root = tmp_path / "environment"
    argument_root = tmp_path / "argument"
    monkeypatch.setenv("MEGURI_MEMORY_MIRROR_ROOT", str(environment_root))

    assert resolve_mirror_root(argument_root) == argument_root.resolve()
    assert resolve_mirror_root(None) == environment_root.resolve()


def test_mirror_root_is_required(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.delenv("MEGURI_MEMORY_MIRROR_ROOT", raising=False)
    with pytest.raises(RuntimeError, match="memory mirror root is required"):
        resolve_mirror_root(None)


def test_worker_actions_are_explicit_and_mutually_exclusive():
    memory_id = uuid4()
    arguments = parse_args(["--worker-id", "mirror-1", "--repair", str(memory_id)])
    assert arguments.repair == [memory_id]

    with pytest.raises(SystemExit):
        parse_args(
            [
                "--worker-id",
                "mirror-1",
                "--continuous",
                "--requeue-dead-letters",
            ]
        )


def test_compose_manages_continuous_file_mirror_with_persistent_volume():
    root = Path(__file__).resolve().parents[3]
    compose = yaml.safe_load(
        (root / "ops" / "compose" / "compose.base.yaml").read_text(encoding="utf-8")
    )
    worker = compose["services"]["file-mirror-worker"]

    assert worker["image"] == "${MEGURI_CORE_IMAGE:?MEGURI_CORE_IMAGE is required}"
    assert worker["restart"] == "unless-stopped"
    assert worker["depends_on"]["migration"]["condition"] == "service_completed_successfully"
    assert worker["environment"]["MEGURI_MEMORY_MIRROR_ROOT"] == (
        "/var/lib/meguri/memory-mirror"
    )
    assert worker["volumes"] == ["memory-mirror:/var/lib/meguri/memory-mirror"]
    assert "--continuous" in worker["command"]
    assert "memory-mirror" in compose["volumes"]
