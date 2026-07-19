"""Generate an immutable Meguri Release Manifest from explicit build inputs."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from datetime import UTC, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SCHEMA = ROOT / "ops" / "manifests" / "release-manifest.schema.json"
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from ops.scripts.check_release_manifest import ManifestError, validate_manifest


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def current_git_commit() -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip().lower()


def parse_assignment(value: str) -> tuple[str, str]:
    if "=" not in value:
        raise argparse.ArgumentTypeError("expected NAME=VALUE")
    name, assigned = value.split("=", 1)
    if not name or not assigned:
        raise argparse.ArgumentTypeError("expected non-empty NAME=VALUE")
    return name, assigned


def atomic_write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, path)
    except BaseException:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise


def build_manifest(args: argparse.Namespace) -> dict:
    image_digests = dict(args.image_digest)
    manifest = {
        "manifest_schema_version": 1,
        "release_id": args.release_id,
        "environment": args.environment,
        "git_commit": (args.git_commit or current_git_commit()).lower(),
        "image_digests": image_digests,
        "data_build_id": args.data_build_id,
        "prompt_sha256": sha256_file(args.prompt_file),
        "response_schema_sha256": sha256_file(args.response_schema_file),
        "expression_map_sha256": sha256_file(args.expression_map_file),
        "database_revision": args.database_revision,
        "embedding_model_revision": args.embedding_model_revision,
        "llm_base_model": args.llm_base_model,
        "llm_adapter_revision": None if args.llm_adapter_revision in {None, "none", "null"} else args.llm_adapter_revision,
        "llm_adapter_sha256": args.llm_adapter_sha256,
        "llm_generation_profile_id": args.llm_generation_profile_id,
        "llm_generation_profile_sha256": args.llm_generation_profile_sha256,
        "llm_locked_eval_suite_id": args.llm_locked_eval_suite_id,
        "llm_locked_eval_source_build_id": args.llm_locked_eval_source_build_id,
        "llm_locked_eval_manifest_sha256": args.llm_locked_eval_manifest_sha256,
        "llm_independent_suite_validation_sha256": args.llm_independent_suite_validation_sha256,
        "model_registry_id": None if args.model_registry_id in {None, "none", "null"} else args.model_registry_id,
        "tests": {
            "python": args.python_tests,
            "typescript": args.typescript_tests,
            "integration": args.integration_tests,
        },
        "generated_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
    }
    validate_manifest(manifest, json.loads(args.schema.read_text(encoding="utf-8")))
    return manifest


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA)
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--release-id", required=True)
    result.add_argument("--environment", choices=("dev", "staging", "production"), required=True)
    result.add_argument("--git-commit")
    result.add_argument("--image-digest", action="append", type=parse_assignment, default=[], required=True)
    result.add_argument("--data-build-id", required=True)
    result.add_argument("--prompt-file", type=Path, required=True)
    result.add_argument("--response-schema-file", type=Path, required=True)
    result.add_argument("--expression-map-file", type=Path, required=True)
    result.add_argument("--database-revision", required=True)
    result.add_argument("--embedding-model-revision", required=True)
    result.add_argument("--llm-base-model", required=True)
    result.add_argument("--llm-adapter-revision")
    result.add_argument("--llm-adapter-sha256")
    result.add_argument("--llm-generation-profile-id")
    result.add_argument("--llm-generation-profile-sha256")
    result.add_argument("--llm-locked-eval-suite-id")
    result.add_argument("--llm-locked-eval-source-build-id")
    result.add_argument("--llm-locked-eval-manifest-sha256")
    result.add_argument("--llm-independent-suite-validation-sha256")
    result.add_argument("--model-registry-id")
    for name in ("python", "typescript", "integration"):
        result.add_argument(
            f"--{name}-tests",
            choices=("passed", "failed", "skipped"),
            required=True,
        )
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        manifest = build_manifest(args)
        atomic_write_json(args.output, manifest)
    except (OSError, subprocess.CalledProcessError, ManifestError, json.JSONDecodeError) as exc:
        print(f"manifest_generation_failed: {exc}")
        return 1
    print(f"generated {args.output} release_id={manifest['release_id']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
