"""Read-only verification that protected server objects remain present and running."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BASELINE = ROOT / "ops" / "baselines" / "protected-server-invariants.json"


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path.name} must contain an object")
    return value


def docker_lines(docker: str, arguments: list[str]) -> list[str]:
    completed = subprocess.run([docker, *arguments], capture_output=True, text=True, check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"read-only Docker command failed with exit code {completed.returncode}")
    return [line.strip() for line in completed.stdout.splitlines() if line.strip()]


def capture_inventory(docker: str) -> dict[str, Any]:
    containers: dict[str, str] = {}
    for line in docker_lines(docker, ["ps", "--format", "{{.Names}}|{{.Status}}"]):
        name, separator, status = line.partition("|")
        if not separator:
            raise RuntimeError("unexpected docker ps output")
        containers[name] = status
    return {
        "containers": containers,
        "networks": docker_lines(docker, ["network", "ls", "--format", "{{.Name}}"]),
        "volumes": docker_lines(docker, ["volume", "ls", "--format", "{{.Name}}"]),
    }


def validate_inventory(
    baseline: dict[str, Any],
    inventory: dict[str, Any],
    *,
    expect_no_meguri: bool = False,
) -> list[str]:
    errors: list[str] = []
    containers = inventory.get("containers") or {}
    networks = set(inventory.get("networks") or [])
    volumes = set(inventory.get("volumes") or [])
    if not isinstance(containers, dict):
        return ["inventory.containers must be an object"]
    for name in baseline.get("required_running_containers") or []:
        status = containers.get(name)
        if status is None:
            errors.append(f"protected container is missing: {name}")
        elif not str(status).startswith("Up "):
            errors.append(f"protected container is not running: {name} ({status})")
    for name in baseline.get("required_networks") or []:
        if name not in networks:
            errors.append(f"protected network is missing: {name}")
    for name in baseline.get("required_named_volumes") or []:
        if name not in volumes:
            errors.append(f"protected named volume is missing: {name}")
    if expect_no_meguri:
        prefixes = tuple(baseline.get("meguri_object_prefixes") or [])
        objects = set(containers) | networks | volumes
        for name in sorted(objects):
            if name.startswith(prefixes):
                errors.append(f"unexpected Meguri server object exists: {name}")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--inventory", type=Path)
    source.add_argument("--live", action="store_true")
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--expect-no-meguri", action="store_true")
    parser.add_argument("--write-inventory", type=Path)
    args = parser.parse_args(argv)
    try:
        baseline = load_json(args.baseline)
        inventory = capture_inventory(args.docker) if args.live else load_json(args.inventory)
        if args.write_inventory:
            args.write_inventory.write_text(json.dumps(inventory, indent=2) + "\n", encoding="utf-8")
        errors = validate_inventory(baseline, inventory, expect_no_meguri=args.expect_no_meguri)
    except (OSError, ValueError, TypeError, KeyError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"server_invariant_error: {exc}", file=sys.stderr)
        return 2
    if errors:
        for error in errors:
            print(f"FAIL {error}", file=sys.stderr)
        return 1
    print("PASS protected server containers, networks, and named volumes satisfy invariants")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
