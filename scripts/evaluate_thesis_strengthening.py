#!/usr/bin/env python3
"""Evaluate reviewed thesis-strengthening observations without live access."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.thesis_evaluation import EvaluationInputError, evaluate_file, write_report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="versioned observation JSON")
    parser.add_argument("--output", type=Path, help="write report JSON to this path")
    args = parser.parse_args()
    try:
        report = evaluate_file(args.input)
        rendered = write_report(report, args.output)
    except EvaluationInputError as error:
        print(f"evaluation input error: {error}", file=sys.stderr)
        return 2
    except OSError as error:
        print(f"evaluation I/O error: {error}", file=sys.stderr)
        return 2
    if args.output is None:
        sys.stdout.write(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
