from __future__ import annotations

import unittest
from pathlib import Path

from training.llm.scripts.common import CONFIG_ROOT, load_yaml
from training.llm.scripts.probe_environment import validate_config


class LlmEnvironmentConfigTests(unittest.TestCase):
    def test_main_model_is_pinned_and_text_only(self) -> None:
        config = load_yaml(CONFIG_ROOT / "qwen35_4b_bf16_lora.yaml")
        self.assertEqual(validate_config(config), [])
        self.assertEqual(config["model"]["repo_id"], "Qwen/Qwen3.5-4B")
        self.assertFalse(config["model"]["load_in_4bit"])
        self.assertFalse(config["model"]["train_vision_layers"])
        self.assertTrue(config["training"]["assistant_only_loss"])
        self.assertEqual(config["training"]["max_seq_length"], 2048)

    def test_comparison_model_is_nf4_qlora(self) -> None:
        config = load_yaml(CONFIG_ROOT / "qwen3_4b_qlora.yaml")
        self.assertEqual(validate_config(config), [])
        self.assertTrue(config["model"]["load_in_4bit"])
        self.assertEqual(config["model"]["quantization"]["type"], "nf4")

    def test_eight_b_route_is_disabled(self) -> None:
        config = load_yaml(CONFIG_ROOT / "qwen3_8b_qlora.yaml")
        self.assertEqual(validate_config(config), [])
        self.assertFalse(config["enabled"])
        self.assertEqual(len(config["enablement_gates"]), 5)

    def test_all_model_revisions_are_commit_shas(self) -> None:
        for path in Path(CONFIG_ROOT).glob("*.yaml"):
            config = load_yaml(path)
            revision = config["model"]["revision"]
            self.assertEqual(len(revision), 40, path.name)
            int(revision, 16)


if __name__ == "__main__":
    unittest.main()
