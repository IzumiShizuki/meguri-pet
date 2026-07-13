"""Read-only production gate checker.

This script never contacts a server and never mutates a service. It is intended
to make an unsafe deployment fail closed before any separate deployment tool is
considered.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GATE_PATH = ROOT / "configs" / "production_gate.json"


def load_gate(path: Path = GATE_PATH) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or not isinstance(value.get("checks"), dict):
        raise ValueError("production gate must contain an object-shaped checks field")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only Meguri production gate checker")
    parser.add_argument("--json", action="store_true", help="print the gate document as JSON")
    args = parser.parse_args()
    gate = load_gate()
    if args.json:
        print(json.dumps(gate, ensure_ascii=False, indent=2))
    else:
        print(f"status={gate.get('status', 'unknown')} mutation_allowed={gate.get('mutation_allowed', False)}")
        for name, passed in gate["checks"].items():
            print(f"{'PASS' if passed else 'BLOCK'} {name}")
        print(gate.get("reason", ""))
    return 0 if gate.get("status") == "ready" and gate.get("mutation_allowed") is True else 2


if __name__ == "__main__":
    raise SystemExit(main())
