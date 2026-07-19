"""Deploy an immutable staging release and automatically restore last-good."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from ops.deployment.release import DeploymentController, DeploymentError, preflight_release


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--state-dir", type=Path, default=Path("/opt/meguri/staging/state"))
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--compose", help="standalone Compose executable for a remote control plane")
    parser.add_argument("--health-timeout", type=float, default=180)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    try:
        candidate = preflight_release(args.env_file.resolve(), args.manifest.resolve())
        controller = DeploymentController(
            args.state_dir,
            docker=args.docker,
            compose=args.compose,
            health_timeout=args.health_timeout,
        )
        controller.validate_compose(candidate)
        if not args.dry_run:
            controller.deploy(candidate)
    except (OSError, ValueError, KeyError, TypeError, DeploymentError) as exc:
        print(f"staging_deploy_failed: {exc}", file=sys.stderr)
        return 1
    action = "validated" if args.dry_run else "deployed"
    print(f"staging_{action} release_id={candidate['release_id']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
