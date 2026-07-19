from __future__ import annotations

import argparse
import json
from pathlib import Path

from services.meguri_core.schemas import LlmResponse
from training.llm.eval.backends import LocalUnslothBackend
from training.llm.eval.eval_cases import frozen_prompt_contract
from training.llm.generation_profile import resolve_generation_settings
from training.llm.scripts.common import PipelineError, canonical_json, load_yaml


def main() -> int:
    parser = argparse.ArgumentParser(description="Run one pinned base + adapter JSON inference")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--adapter", type=Path)
    parser.add_argument("--generation-profile", type=Path)
    parser.add_argument("--message", required=True)
    parser.add_argument("--language", choices=["jp", "zh"], default="zh")
    parser.add_argument("--allow-download", action="store_true")
    parser.add_argument("--max-new-tokens", type=int)
    parser.add_argument("--repetition-penalty", type=float)
    parser.add_argument("--no-repeat-ngram-size", type=int)
    parser.add_argument("--force-json-object-start", action="store_true", default=None)
    args = parser.parse_args()
    try:
        prompt, _, _ = frozen_prompt_contract()
        config = load_yaml(args.config)
        generation, _ = resolve_generation_settings(
            args,
            training_config=config,
            adapter_path=args.adapter,
        )
        backend = LocalUnslothBackend(
            config,
            allow_download=args.allow_download,
            adapter_path=args.adapter,
            **generation,
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
