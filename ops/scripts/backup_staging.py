"""Create a checksummed custom-format backup of isolated staging PostgreSQL."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from ops.backup.postgres import BackupError, StagingDatabase, create_backup


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args(argv)
    try:
        metadata = create_backup(
            StagingDatabase(args.env_file.resolve(), docker=args.docker),
            args.output_dir.resolve(),
        )
    except (OSError, ValueError, KeyError, TypeError, BackupError) as exc:
        print(f"staging_backup_failed: {exc}", file=sys.stderr)
        return 1
    print(f"staging_backup_passed metadata={metadata}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
