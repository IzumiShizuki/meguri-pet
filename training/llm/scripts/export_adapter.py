from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from training.llm.scripts.common import (
    PipelineError,
    canonical_json,
    read_json,
    require_clean_git_worktree,
    sha256_file,
    sha256_text,
    utc_now,
    write_json,
)


def adapter_hash(root: Path) -> tuple[str, dict[str, str]]:
    hashes: dict[str, str] = {}
    non_adapter_state = {
        "optimizer.pt",
        "scheduler.pt",
        "rng_state.pth",
        "trainer_state.json",
        "training_args.bin",
    }
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        if path.name in {"export_manifest.json", *non_adapter_state}:
            continue
        hashes[path.relative_to(root).as_posix()] = sha256_file(path)
    if not hashes:
        raise PipelineError("adapter directory contains no files")
    return sha256_text(canonical_json(hashes)), hashes


def export(
    experiment_dir: Path,
    export_root: Path,
    selection_path: Path | None = None,
    *,
    export_commit: str | None = None,
) -> Path:
    experiment_path = experiment_dir / "experiment_manifest.json"
    experiment = read_json(experiment_path)
    if experiment.get("status") != "pass":
        raise PipelineError("only a passing experiment can be exported")
    selection = read_json(selection_path) if selection_path else None
    source_value = (
        selection.get("selected", {}).get("adapter_path")
        if selection is not None
        else experiment.get("final_adapter")
    )
    source = Path(str(source_value or ""))
    if not source.is_dir():
        raise PipelineError("experiment final_adapter is unavailable")
    digest, hashes = adapter_hash(source)
    if selection is not None and selection.get("selected", {}).get("adapter_sha256") != digest:
        raise PipelineError("selected checkpoint hash does not match its validation selection")
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
    if export_commit is not None and require_clean_git_worktree() != export_commit:
        raise PipelineError("Git commit changed while adapter export was running")
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
            "validation_selection": str(selection_path.resolve()) if selection_path else None,
            "validation_selection_sha256": sha256_file(selection_path) if selection_path else None,
            "experiment_manifest_sha256": sha256_file(experiment_path),
            "export_code_commit": export_commit,
            "files": hashes,
            "created_at": utc_now(),
        },
    )
    return destination


def main() -> int:
    parser = argparse.ArgumentParser(description="Export and hash a reproducible LoRA adapter")
    parser.add_argument("experiment_dir", type=Path)
    parser.add_argument("--export-root", type=Path, required=True)
    parser.add_argument("--selection", type=Path)
    args = parser.parse_args()
    try:
        export_commit = require_clean_git_worktree()
        output = export(
            args.experiment_dir,
            args.export_root,
            args.selection,
            export_commit=export_commit,
        )
    except PipelineError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "pass", "adapter_dir": str(output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
