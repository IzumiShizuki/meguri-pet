"""Restore the recorded staging rollback target without rerunning migration."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from ops.deployment.release import DeploymentController, DeploymentError


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, default=Path("/opt/meguri/staging/state"))
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--health-timeout", type=float, default=180)
    args = parser.parse_args(argv)
    try:
        DeploymentController(
            args.state_dir,
            docker=args.docker,
            health_timeout=args.health_timeout,
        ).rollback()
    except (OSError, ValueError, KeyError, TypeError, DeploymentError) as exc:
        print(f"staging_rollback_failed: {exc}", file=sys.stderr)
        return 1
    print("staging_rollback_passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
