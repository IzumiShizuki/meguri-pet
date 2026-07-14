from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from training.llm.scripts.common import PipelineError, canonical_json, read_json, sha256_file, sha256_text, utc_now, write_json


def adapter_hash(root: Path) -> tuple[str, dict[str, str]]:
    hashes: dict[str, str] = {}
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        if path.name in {"export_manifest.json"}:
            continue
        hashes[path.relative_to(root).as_posix()] = sha256_file(path)
    if not hashes:
        raise PipelineError("adapter directory contains no files")
    return sha256_text(canonical_json(hashes)), hashes


def export(experiment_dir: Path, export_root: Path) -> Path:
    experiment = read_json(experiment_dir / "experiment_manifest.json")
    if experiment.get("status") != "pass":
        raise PipelineError("only a passing experiment can be exported")
    source = Path(str(experiment.get("final_adapter") or ""))
    if not source.is_dir():
        raise PipelineError("experiment final_adapter is unavailable")
    digest, hashes = adapter_hash(source)
    model_id = f"{experiment['experiment_id']}-{digest[:12]}"
    destination = export_root.resolve() / model_id
    if destination.exists():
        raise PipelineError(f"refusing to overwrite adapter export: {destination}")
    destination.mkdir(parents=True, exist_ok=False)
    for relative in hashes:
        source_path = source / relative
        destination_path = destination / relative
        destination_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_path, destination_path)
    copied_digest, copied_hashes = adapter_hash(destination)
    if copied_digest != digest or copied_hashes != hashes:
        raise PipelineError("exported adapter hash verification failed")
    write_json(
        destination / "export_manifest.json",
        {
            "schema_version": 1,
            "model_id": model_id,
            "experiment_id": experiment["experiment_id"],
            "base_model_repo": experiment["base_model_repo"],
            "base_model_revision": experiment["base_model_revision"],
            "tokenizer_revision": experiment["tokenizer_revision"],
            "adapter_sha256": digest,
            "files": hashes,
            "created_at": utc_now(),
        },
    )
    return destination


def main() -> int:
    parser = argparse.ArgumentParser(description="Export and hash a reproducible LoRA adapter")
    parser.add_argument("experiment_dir", type=Path)
    parser.add_argument("--export-root", type=Path, required=True)
    args = parser.parse_args()
    try:
        output = export(args.experiment_dir, args.export_root)
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", "adapter_dir": str(output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
