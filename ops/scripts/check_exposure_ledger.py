"""Validate the exposure ledger and its relationship to Meguri Compose ports."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from ops.scripts.check_environment_isolation import ENVIRONMENTS, load_environment


DEFAULT_LEDGER = ROOT / "ops" / "exposure" / "temporary-public-exposure.yaml"
REQUIRED_FIELDS = {
    "id",
    "scope",
    "environment",
    "service",
    "host",
    "ports",
    "protocol",
    "declared_binding",
    "observed_reachable",
    "authentication",
    "data_classification",
    "owner",
    "approval_status",
    "gate",
    "close_condition",
    "evidence",
}


def load_ledger(path: Path = DEFAULT_LEDGER) -> dict[str, Any]:
    value = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("ledger must contain an object")
    return value


def published_ports(service: dict[str, Any]) -> set[tuple[str, int]]:
    result: set[tuple[str, int]] = set()
    for entry in service.get("ports") or []:
        if isinstance(entry, str):
            parts = entry.rsplit(":", 2)
            if len(parts) == 3:
                host, published = parts[0], parts[1]
            elif len(parts) == 2:
                host, published = "0.0.0.0", parts[0]
            else:
                continue
            result.add((host, int(published)))
        elif isinstance(entry, dict):
            result.add((str(entry.get("host_ip") or "0.0.0.0"), int(entry["published"])))
    return result


def validate_ledger(ledger: dict[str, Any], *, production_gate: bool = False) -> list[str]:
    errors: list[str] = []
    if ledger.get("schema_version") != 1:
        errors.append("ledger.schema_version must be 1")
    entries = ledger.get("entries")
    if not isinstance(entries, list):
        return errors + ["ledger.entries must be an array"]

    identities: set[str] = set()
    endpoints: set[tuple[str, int, str]] = set()
    registered: set[tuple[str, str, int]] = set()
    for index, entry in enumerate(entries):
        path = f"entries[{index}]"
        if not isinstance(entry, dict):
            errors.append(f"{path} must be an object")
            continue
        missing = REQUIRED_FIELDS.difference(entry)
        if missing:
            errors.append(f"{path} missing fields: {', '.join(sorted(missing))}")
            continue
        identity = str(entry["id"])
        if identity in identities:
            errors.append(f"{path}.id is duplicated: {identity}")
        identities.add(identity)
        ports = entry["ports"]
        if not isinstance(ports, list) or not ports:
            errors.append(f"{path}.ports must be a non-empty array")
            continue
        if entry["scope"] not in {"existing-protected", "meguri"}:
            errors.append(f"{path}.scope is unsupported")
        if entry["environment"] not in {*ENVIRONMENTS, "shared"}:
            errors.append(f"{path}.environment is unsupported")
        for field in ("authentication", "data_classification", "owner", "close_condition", "evidence"):
            if not str(entry[field]).strip():
                errors.append(f"{path}.{field} must not be empty")
        for port in ports:
            if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535:
                errors.append(f"{path}.ports contains invalid port: {port!r}")
                continue
            endpoint = (str(entry["host"]), port, str(entry["protocol"]))
            if endpoint in endpoints:
                errors.append(f"{path}.ports duplicates endpoint {endpoint}")
            endpoints.add(endpoint)
            if entry["scope"] == "meguri":
                registered.add((str(entry["environment"]), str(entry["host"]), port))
        if entry["scope"] == "meguri" and entry["declared_binding"] == "all-interfaces":
            if entry["approval_status"] != "approved-temporary":
                errors.append(f"{path} public Meguri binding is not approved-temporary")
        if production_gate and entry["gate"] == "blocks-production":
            errors.append(f"production_gate {identity}: unresolved exposure review")

    for environment in ENVIRONMENTS:
        _, compose = load_environment(environment)
        for service_name, service in (compose.get("services") or {}).items():
            for host, port in published_ports(service or {}):
                if (environment, host, port) not in registered:
                    errors.append(
                        f"compose_exposure {environment}.{service_name}: {host}:{port} is absent from the ledger"
                    )
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", type=Path, default=DEFAULT_LEDGER)
    parser.add_argument("--production-gate", action="store_true")
    args = parser.parse_args(argv)
    try:
        errors = validate_ledger(load_ledger(args.ledger), production_gate=args.production_gate)
    except (OSError, ValueError, TypeError, KeyError, yaml.YAMLError) as exc:
        print(f"exposure_ledger_error: {exc}", file=sys.stderr)
        return 2
    if errors:
        for error in errors:
            print(f"FAIL {error}", file=sys.stderr)
        return 1
    print("PASS exposure ledger is structurally complete and matches Compose")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
