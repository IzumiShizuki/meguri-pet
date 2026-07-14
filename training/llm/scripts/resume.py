from __future__ import annotations

import json

from training.llm.scripts.common import PipelineError
from training.llm.scripts.train import parser, run


def main() -> int:
    args = parser().parse_args()
    if args.resume_from_checkpoint is None:
        print(json.dumps({"status": "fail", "error": "--resume-from-checkpoint is required"}))
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
