from __future__ import annotations

import argparse
import copy
import json
import tempfile
import unittest
from pathlib import Path

from ops.scripts.check_release_manifest import ManifestError, check_readiness, validate_manifest
from ops.scripts.generate_release_manifest import build_manifest


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "ops" / "manifests" / "release-manifest.schema.json"
EXAMPLE_PATH = ROOT / "ops" / "manifests" / "example.release-manifest.json"


class ReleaseManifestTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        cls.example = json.loads(EXAMPLE_PATH.read_text(encoding="utf-8"))

    def test_example_matches_schema(self) -> None:
        validate_manifest(self.example, self.schema)

    def test_required_version_fields_fail_closed(self) -> None:
        for field in ("git_commit", "image_digests", "data_build_id", "database_revision"):
            with self.subTest(field=field):
                manifest = copy.deepcopy(self.example)
                manifest.pop(field)
                with self.assertRaisesRegex(ManifestError, field):
                    validate_manifest(manifest, self.schema)

    def test_readiness_rejects_mismatch_and_placeholders(self) -> None:
        args = argparse.Namespace(
            expected_environment="staging",
            expected_build_id="different-build",
            expected_git_commit=None,
            expected_prompt_sha256=None,
            expected_response_schema_sha256=None,
            expected_expression_map_sha256=None,
            expected_database_revision=None,
            expected_embedding_model_revision=None,
            expected_llm_base_model=None,
            expected_llm_adapter_revision=None,
            expected_llm_adapter_sha256=None,
            expected_llm_generation_profile_id=None,
            expected_llm_generation_profile_sha256=None,
            expected_llm_locked_eval_suite_id=None,
            expected_llm_locked_eval_source_build_id=None,
            expected_llm_locked_eval_manifest_sha256=None,
            expected_llm_independent_suite_validation_sha256=None,
            expected_image_digest=[],
            readiness=True,
        )
        errors = check_readiness(self.example, args)
        self.assertTrue(any("environment" in item for item in errors))
        self.assertTrue(any("data_build_id" in item for item in errors))
        self.assertTrue(any("git_commit" in item for item in errors))
        adapter_manifest = copy.deepcopy(self.example)
        adapter_manifest["llm_adapter_revision"] = "adapter-v1"
        adapter_manifest["llm_adapter_sha256"] = "a" * 64
        profile_errors = check_readiness(adapter_manifest, args)
        self.assertTrue(any("llm_generation_profile_id" in item for item in profile_errors))
        self.assertTrue(any("llm_locked_eval_suite_id" in item for item in profile_errors))
        self.assertTrue(
            any("llm_independent_suite_validation_sha256" in item for item in profile_errors)
        )

    def test_generator_hashes_artifacts_and_validates_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prompt = root / "prompt.txt"
            response_schema = root / "response.json"
            expression_map = root / "expressions.json"
            prompt.write_text("prompt", encoding="utf-8")
            response_schema.write_text("{}", encoding="utf-8")
            expression_map.write_text("{}", encoding="utf-8")
            args = argparse.Namespace(
                schema=SCHEMA_PATH,
                release_id="meguri-staging-test-r001",
                environment="staging",
                git_commit="a" * 40,
                image_digest=[("core", "sha256:" + "b" * 64)],
                data_build_id="meguri_v2_02c3db0c507d7c2d",
                prompt_file=prompt,
                response_schema_file=response_schema,
                expression_map_file=expression_map,
                database_revision="memory_0001",
                embedding_model_revision="bge-m3@revision",
                llm_base_model="model@revision",
                llm_adapter_revision="adapter@sha256",
                llm_adapter_sha256="c" * 64,
                llm_generation_profile_id="decode-v2",
                llm_generation_profile_sha256="d" * 64,
                llm_locked_eval_suite_id="locked-v2",
                llm_locked_eval_source_build_id="new-eval-build-v2",
                llm_locked_eval_manifest_sha256="e" * 64,
                llm_independent_suite_validation_sha256="f" * 64,
                model_registry_id="meguri-text-test",
                python_tests="passed",
                typescript_tests="passed",
                integration_tests="passed",
            )
            manifest = build_manifest(args)
            validate_manifest(manifest, self.schema)
            self.assertEqual(manifest["git_commit"], "a" * 40)
            self.assertNotEqual(manifest["prompt_sha256"], manifest["response_schema_sha256"])


if __name__ == "__main__":
    unittest.main()
