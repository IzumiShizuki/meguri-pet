from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from training.llm.scripts.common import LLM_ROOT, PipelineError, read_json, utc_now


def _write(path: Path, value: dict) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    if temporary.exists():
        raise PipelineError(f"stale routing temporary file exists: {temporary}")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    os.replace(temporary, path)


def switch(registry_path: Path, routing_path: Path, target: str) -> dict:
    registry = read_json(registry_path)
    routing = read_json(routing_path)
    if target == "candidate":
        model_id = routing.get("candidate_model_id")
        allowed = {"staging_candidate", "staging_active"}
        enabled = True
    else:
        model_id = routing.get("last_good_model_id")
        allowed = {"staging_active", "production_candidate", "production_active"}
        enabled = False
    entry = next((item for item in registry.get("models", []) if item.get("model_id") == model_id), None)
    if entry is None:
        raise PipelineError(f"{target} model is not registered")
    if entry.get("status") not in allowed:
        raise PipelineError(f"{target} model status is not activation eligible")
    routing["candidate_enabled"] = enabled
    routing["updated_at"] = utc_now()
    _write(routing_path, routing)
    return {"active_model_id": model_id, "candidate_enabled": enabled, "rebuild_required": False}


def main() -> int:
    parser = argparse.ArgumentParser(description="Switch staging between candidate and last-good without rebuilding")
    parser.add_argument("target", choices=["candidate", "last-good"])
    parser.add_argument("--registry", type=Path, default=LLM_ROOT / "registry" / "model_registry.json")
    parser.add_argument("--routing", type=Path, default=LLM_ROOT / "gateway" / "routing_state.json")
    args = parser.parse_args()
    try:
        result = switch(args.registry, args.routing, args.target)
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", **result}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
