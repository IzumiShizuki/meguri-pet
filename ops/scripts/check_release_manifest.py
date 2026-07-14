"""Validate a Meguri Release Manifest and optional readiness expectations."""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SCHEMA = ROOT / "ops" / "manifests" / "release-manifest.schema.json"


class ManifestError(ValueError):
    """Raised when a release manifest is structurally or semantically invalid."""


def type_matches(value: Any, expected: str) -> bool:
    return {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "number": isinstance(value, (int, float)) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
        "null": value is None,
    }[expected]


def resolve_ref(root_schema: dict[str, Any], reference: str) -> dict[str, Any]:
    if not reference.startswith("#/"):
        raise ManifestError(f"unsupported schema reference: {reference}")
    current: Any = root_schema
    for part in reference[2:].split("/"):
        current = current[part.replace("~1", "/").replace("~0", "~")]
    if not isinstance(current, dict):
        raise ManifestError(f"schema reference does not resolve to an object: {reference}")
    return current


def validate_value(value: Any, schema: dict[str, Any], root_schema: dict[str, Any], path: str) -> list[str]:
    if "$ref" in schema:
        return validate_value(value, resolve_ref(root_schema, schema["$ref"]), root_schema, path)
    errors: list[str] = []
    if "const" in schema and value != schema["const"]:
        errors.append(f"{path}: expected constant {schema['const']!r}")
    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{path}: expected one of {schema['enum']!r}")
    expected_type = schema.get("type")
    if expected_type is not None:
        expected_types = expected_type if isinstance(expected_type, list) else [expected_type]
        if not any(type_matches(value, item) for item in expected_types):
            return [f"{path}: expected type {expected_types!r}, got {type(value).__name__}"]
    if isinstance(value, str):
        if len(value) < int(schema.get("minLength", 0)):
            errors.append(f"{path}: string is shorter than minLength")
        if pattern := schema.get("pattern"):
            if re.fullmatch(pattern, value) is None:
                errors.append(f"{path}: value does not match {pattern!r}")
        if schema.get("format") == "date-time":
            try:
                datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError:
                errors.append(f"{path}: invalid date-time")
    if isinstance(value, dict):
        required = schema.get("required") or []
        for key in required:
            if key not in value:
                errors.append(f"{path}.{key}: required field is missing")
        properties = schema.get("properties") or {}
        additional = schema.get("additionalProperties", True)
        property_name_schema = schema.get("propertyNames")
        if len(value) < int(schema.get("minProperties", 0)):
            errors.append(f"{path}: object has fewer than minProperties")
        for key, item in value.items():
            child_path = f"{path}.{key}"
            if property_name_schema:
                errors.extend(validate_value(key, property_name_schema, root_schema, f"{child_path}<name>"))
            if key in properties:
                errors.extend(validate_value(item, properties[key], root_schema, child_path))
            elif additional is False:
                errors.append(f"{child_path}: additional property is forbidden")
            elif isinstance(additional, dict):
                errors.extend(validate_value(item, additional, root_schema, child_path))
    return errors


def validate_manifest(manifest: dict[str, Any], schema: dict[str, Any]) -> None:
    errors = validate_value(manifest, schema, schema, "manifest")
    environment = manifest.get("environment")
    release_id = manifest.get("release_id")
    if isinstance(environment, str) and isinstance(release_id, str):
        if not release_id.startswith(f"meguri-{environment}-"):
            errors.append("manifest.release_id: prefix does not match environment")
    if errors:
        raise ManifestError("\n".join(errors))


def placeholder(value: Any) -> bool:
    if value is None:
        return False
    text = str(value).strip().lower()
    if any(marker in text for marker in ("replace-with", "not-configured", "unknown", "example")):
        return True
    return bool(text) and len(set(text)) == 1 and text[0] in "0123456789abcdef"


def parse_assignment(value: str) -> tuple[str, str]:
    if "=" not in value:
        raise argparse.ArgumentTypeError("expected NAME=VALUE")
    return tuple(value.split("=", 1))  # type: ignore[return-value]


def check_readiness(manifest: dict[str, Any], args: argparse.Namespace) -> list[str]:
    errors: list[str] = []
    expected = {
        "environment": args.expected_environment,
        "data_build_id": args.expected_build_id,
        "git_commit": args.expected_git_commit,
        "prompt_sha256": args.expected_prompt_sha256,
        "response_schema_sha256": args.expected_response_schema_sha256,
        "expression_map_sha256": args.expected_expression_map_sha256,
        "database_revision": args.expected_database_revision,
        "embedding_model_revision": args.expected_embedding_model_revision,
        "llm_base_model": args.expected_llm_base_model,
        "llm_adapter_revision": args.expected_llm_adapter_revision,
    }
    for field, expected_value in expected.items():
        if expected_value is not None and manifest.get(field) != expected_value:
            errors.append(f"{field}: manifest={manifest.get(field)!r} expected={expected_value!r}")
    for name, digest in args.expected_image_digest:
        actual = (manifest.get("image_digests") or {}).get(name)
        if actual != digest:
            errors.append(f"image_digests.{name}: manifest={actual!r} expected={digest!r}")

    readiness = args.readiness or manifest.get("environment") in {"staging", "production"}
    if readiness:
        critical_fields = (
            "git_commit",
            "data_build_id",
            "prompt_sha256",
            "response_schema_sha256",
            "expression_map_sha256",
            "database_revision",
            "embedding_model_revision",
            "llm_base_model",
        )
        for field in critical_fields:
            if placeholder(manifest.get(field)):
                errors.append(f"{field}: placeholder value is not readiness-safe")
        for name, digest in (manifest.get("image_digests") or {}).items():
            if placeholder(digest.removeprefix("sha256:")):
                errors.append(f"image_digests.{name}: placeholder digest is not readiness-safe")
        for test_name, status in (manifest.get("tests") or {}).items():
            if status != "passed":
                errors.append(f"tests.{test_name}: readiness requires passed, got {status!r}")
    return errors


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("manifest", type=Path)
    result.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA)
    result.add_argument("--readiness", action="store_true")
    result.add_argument("--expected-environment", choices=("dev", "staging", "production"))
    result.add_argument("--expected-build-id")
    result.add_argument("--expected-git-commit")
    result.add_argument("--expected-prompt-sha256")
    result.add_argument("--expected-response-schema-sha256")
    result.add_argument("--expected-expression-map-sha256")
    result.add_argument("--expected-database-revision")
    result.add_argument("--expected-embedding-model-revision")
    result.add_argument("--expected-llm-base-model")
    result.add_argument("--expected-llm-adapter-revision")
    result.add_argument("--expected-image-digest", action="append", type=parse_assignment, default=[])
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        schema = json.loads(args.schema.read_text(encoding="utf-8"))
        if not isinstance(manifest, dict) or not isinstance(schema, dict):
            raise ManifestError("manifest and schema must contain JSON objects")
        validate_manifest(manifest, schema)
        readiness_errors = check_readiness(manifest, args)
        if readiness_errors:
            raise ManifestError("\n".join(readiness_errors))
    except (OSError, json.JSONDecodeError, ManifestError) as exc:
        print(f"FAIL release manifest\n{exc}")
        return 1
    print(f"PASS release manifest release_id={manifest['release_id']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

