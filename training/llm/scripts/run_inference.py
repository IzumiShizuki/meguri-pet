from __future__ import annotations

import argparse
import json
from pathlib import Path

from services.meguri_core.schemas import LlmResponse
from training.llm.eval.backends import LocalUnslothBackend
from training.llm.eval.eval_cases import frozen_prompt_contract
from training.llm.scripts.common import PipelineError, canonical_json, load_yaml


def main() -> int:
    parser = argparse.ArgumentParser(description="Run one pinned base + adapter JSON inference")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--adapter", type=Path)
    parser.add_argument("--message", required=True)
    parser.add_argument("--language", choices=["jp", "zh"], default="zh")
    parser.add_argument("--allow-download", action="store_true")
    args = parser.parse_args()
    try:
        prompt, _, _ = frozen_prompt_contract()
        backend = LocalUnslothBackend(
            load_yaml(args.config),
            allow_download=args.allow_download,
            adapter_path=args.adapter,
            max_new_tokens=256,
        )
        context = {
            "runtime_state": {
                "client_id": "website",
                "mode": "private",
                "relationship_profile": "pursuit",
                "outfit_code": "03",
                "local_time": "2026-07-14T20:00:00+08:00",
                "is_holiday": False,
                "voice_enabled": False,
                "screen_context_enabled": False,
                "allowed_expression_tags": [
                    "affectionate", "angry", "confused", "embarrassed", "excited", "happy",
                    "neutral", "sad", "sleepy", "surprised", "teasing", "worried"
                ],
            },
            "user_message": args.message,
            "canon_examples": [],
            "long_term_memories": [],
            "recent_context": [],
        }
        result = backend.generate(prompt, canonical_json(context))
        payload = json.loads(result.raw_output)
        validated = LlmResponse.model_validate(payload)
    except Exception as exc:
        error = str(exc) if isinstance(exc, PipelineError) else type(exc).__name__
        print(json.dumps({"status": "fail", "error": error}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", "response": validated.model_dump(mode="json")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
