from __future__ import annotations

import json

from training.llm.scripts.common import PipelineError
from training.llm.scripts.train import parser, run


def main() -> int:
    args = parser().parse_args()
    args.smoke = True
    if not 100 <= args.smoke_samples <= 200:
        print(json.dumps({"status": "fail", "error": "L-006 smoke samples must be within 100..200"}))
        return 2
    if not 50 <= args.smoke_steps <= 100:
        print(json.dumps({"status": "fail", "error": "L-006 smoke steps must be within 50..100"}))
        return 2
    try:
        output = run(args)
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", "experiment_dir": str(output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
