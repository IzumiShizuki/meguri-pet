from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
HANDOFF = ROOT / "docs" / "contracts" / "llm-staging-handoff.md"


class LlmStagingHandoffDocTests(unittest.TestCase):
    def test_handoff_contract_exists_and_covers_required_runtime_fields(self) -> None:
        content = HANDOFF.read_text(encoding="utf-8")
        required_markers = [
            "model_registry_id",
            "rollback_model_id",
            "llm_base_model",
            "base_revision",
            "tokenizer_revision",
            "llm_adapter_revision",
            "llm_adapter_sha256",
            "prompt_sha256",
            "response_schema_sha256",
            "data_build_id",
            "MEGURI_LLM_TIMEOUT_SECONDS",
            "MEGURI_LLM_MAX_CONCURRENCY",
            "schema-invalid provider output remains fail closed",
            "Production remains a separate approval path",
        ]
        for marker in required_markers:
            with self.subTest(marker=marker):
                self.assertIn(marker, content)


if __name__ == "__main__":
    unittest.main()
