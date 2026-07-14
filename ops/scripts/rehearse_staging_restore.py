"""Restore a staging backup into an isolated temporary database and verify it."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from ops.backup.postgres import BackupError, StagingDatabase, rehearse_restore


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args(argv)
    try:
        result = rehearse_restore(
            StagingDatabase(args.env_file.resolve(), docker=args.docker),
            args.metadata.resolve(),
            args.target,
        )
    except (OSError, ValueError, KeyError, TypeError, BackupError) as exc:
        print(f"staging_restore_rehearsal_failed: {exc}", file=sys.stderr)
        return 1
    print(
        "staging_restore_rehearsal_passed "
        f"revision={result['restore_rehearsal']['database_revision']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
