from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from services.meguri_core.deployment import ReadinessEvaluator, sha256_file
from services.meguri_core.memory import FakeMemoryProvider
from services.meguri_core.providers import MockLLMProvider
from services.meguri_core.secrets import SecretConfigurationError, read_secret


class FakeOrchestrator:
    def __init__(self) -> None:
        self.memory = FakeMemoryProvider()
        self.llm = MockLLMProvider()


class SecretFileTests(unittest.TestCase):
    def test_file_secret_loads_without_inline_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "secret.txt"
            path.write_text("file-value\n", encoding="utf-8")
            values = {"MEGURI_SAMPLE_FILE": str(path)}
            self.assertEqual(read_secret(values, "MEGURI_SAMPLE"), "file-value")

    def test_inline_or_ambiguous_secret_fails_closed(self) -> None:
        with self.assertRaises(SecretConfigurationError):
            read_secret({"MEGURI_SAMPLE": "inline"}, "MEGURI_SAMPLE")
        with self.assertRaises(SecretConfigurationError):
            read_secret(
                {"MEGURI_SAMPLE": "inline", "MEGURI_SAMPLE_FILE": "unused"},
                "MEGURI_SAMPLE",
            )

    def test_secret_file_must_be_absolute_and_bounded(self) -> None:
        with self.assertRaisesRegex(SecretConfigurationError, "absolute path"):
            read_secret({"MEGURI_SAMPLE_FILE": "relative-secret.txt"}, "MEGURI_SAMPLE")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "oversized-secret.txt"
            path.write_bytes(b"x" * 8193)
            with self.assertRaisesRegex(SecretConfigurationError, "unexpectedly large"):
                read_secret({"MEGURI_SAMPLE_FILE": str(path)}, "MEGURI_SAMPLE")


class ReadinessEvaluatorTests(unittest.IsolatedAsyncioTestCase):
    async def test_managed_environment_passes_only_with_matching_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prompt = root / "prompt.txt"
            schema = root / "schema.json"
            expression = root / "expression.json"
            prompt.write_text("Meguri prompt", encoding="utf-8")
            schema.write_text('{"type":"object"}', encoding="utf-8")
            expression.write_text('{"expressions":{}}', encoding="utf-8")

            secrets: dict[str, str] = {}
            for name, value in {
                "database-url": "postgresql://app:password@postgres/meguri_dev",
                "jwt": "jwt-value",
                "astrbot": "astrbot-value",
            }.items():
                path = root / f"{name}.txt"
                path.write_text(value, encoding="utf-8")
                secrets[name] = str(path)

            manifest = {
                "environment": "dev",
                "release_id": "meguri-dev-test-r001",
                "data_build_id": "meguri_test_build",
                "database_revision": "20260714_0004",
                "embedding_model_revision": "embedding-r1",
                "llm_base_model": "mock-v1",
                "llm_adapter_revision": None,
                "llm_adapter_sha256": None,
                "llm_generation_profile_id": None,
                "llm_generation_profile_sha256": None,
                "prompt_sha256": sha256_file(prompt),
                "response_schema_sha256": sha256_file(schema),
                "expression_map_sha256": sha256_file(expression),
            }
            manifest_path = root / "release-manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            env = {
                "MEGURI_ENV": "dev",
                "MEGURI_RELEASE_ID": "meguri-dev-test-r001",
                "MEGURI_DATA_BUILD_ID": "meguri_test_build",
                "MEGURI_DATABASE_REVISION": "20260714_0004",
                "MEGURI_EMBEDDING_MODEL_REVISION": "embedding-r1",
                "MEGURI_LLM_BASE_MODEL_REVISION": "mock-v1",
                "MEGURI_LLM_ADAPTER_REVISION": "none",
                "MEGURI_LLM_ADAPTER_SHA256": "none",
                "MEGURI_LLM_GENERATION_PROFILE_ID": "none",
                "MEGURI_LLM_GENERATION_PROFILE_SHA256": "none",
                "MEGURI_RELEASE_MANIFEST_PATH": str(manifest_path),
                "MEGURI_EXPRESSION_MAP_PATH": str(expression),
                "MEGURI_DATABASE_URL_FILE": secrets["database-url"],
                "MEGURI_JWT_SECRET_FILE": secrets["jwt"],
                "MEGURI_ASTRBOT_SHARED_TOKEN_FILE": secrets["astrbot"],
                "MEGURI_MEMORY_PROVIDER": "fake",
                "MEGURI_LLM_PROVIDER": "mock",
            }

            async def database_probe(_url: str) -> str:
                return "20260714_0004"

            evaluator = ReadinessEvaluator(
                FakeOrchestrator(),
                env=env,
                build_id="meguri_test_build",
                prompt_path=prompt,
                response_schema_path=schema,
                database_probe=database_probe,
            )
            result = await evaluator.evaluate()
            self.assertEqual(result["status"], "ready", result.get("failures"))
            self.assertTrue(all(value == "passed" for value in result["checks"].values()))

            env["MEGURI_DATABASE_REVISION"] = "wrong-revision"
            failed = await evaluator.evaluate()
            self.assertEqual(failed["status"], "not_ready")
            self.assertEqual(failed["checks"]["release_identity"], "failed")
            self.assertEqual(failed["checks"]["database_revision"], "failed")

    async def test_unmanaged_local_mode_is_explicit(self) -> None:
        evaluator = ReadinessEvaluator(FakeOrchestrator(), env={}, build_id="local-build")
        result = await evaluator.evaluate()
        self.assertEqual(result["status"], "ready")
        self.assertEqual(result["checks"], {"local_unmanaged": "passed"})


if __name__ == "__main__":
    unittest.main()
