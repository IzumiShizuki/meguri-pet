from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run the one canonical adapter fixture suite across Website, AstrBot, and AIRI."
    )
    parser.add_argument(
        "--airi-root",
        type=Path,
        default=ROOT.parent / "airi-meguri",
    )
    args = parser.parse_args()
    airi_package = args.airi_root / "packages" / "meguri-airi-adapter"
    if not airi_package.is_dir():
        raise SystemExit(f"AIRI adapter package not found: {airi_package}")

    commands = [
        [
            sys.executable,
            str(ROOT / "scripts" / "check_adapter_contract_consistency.py"),
            "--airi-root",
            str(args.airi_root),
        ],
        [
            "node",
            "--test",
            "tests-ts/adapter-protocol-v1.test.ts",
            "tests-ts/website-adapter.test.ts",
        ],
        [
            sys.executable,
            "-m",
            "pytest",
            "tests/test_adapter_protocol_canonical_fixture.py",
            "tests/test_astrbot_gateway.py",
            "-q",
        ],
        [
            "pnpm.cmd" if os.name == "nt" else "pnpm",
            "exec",
            "vitest",
            "run",
            "src/contract-fixture.test.ts",
            "src/index.test.ts",
            "src/protocol.test.ts",
        ],
    ]
    working_directories = [ROOT, ROOT, ROOT, airi_package]
    for command, cwd in zip(commands, working_directories, strict=True):
        print(f"[{cwd}] {' '.join(command)}", flush=True)
        subprocess.run(command, cwd=cwd, check=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
