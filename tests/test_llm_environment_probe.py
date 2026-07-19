from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from training.llm.scripts.common import CONFIG_ROOT, load_yaml, sha256_text
from training.llm.scripts.probe_environment import (
    REQUIRED_PACKAGES,
    pip_freeze_snapshot,
    static_probe,
    validate_config,
)
from training.llm.scripts.modeling import configure_compile_cache


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

    def test_pip_freeze_snapshot_records_full_environment_lock(self) -> None:
        result = subprocess.CompletedProcess(
            args=["python", "-m", "pip", "freeze"],
            returncode=0,
            stdout="alpha==1.0\nbeta==2.0\n",
        )
        with patch("training.llm.scripts.probe_environment.subprocess.run", return_value=result):
            snapshot = pip_freeze_snapshot("C:/Python/python.exe")
        self.assertEqual(snapshot["status"], "pass")
        self.assertEqual(snapshot["command"], ["C:/Python/python.exe", "-m", "pip", "freeze"])
        self.assertEqual(snapshot["packages"], ["alpha==1.0", "beta==2.0"])
        self.assertEqual(snapshot["line_count"], 2)
        self.assertEqual(snapshot["sha256"], sha256_text("alpha==1.0\nbeta==2.0\n"))

    def test_static_probe_fails_closed_without_environment_lock(self) -> None:
        config = load_yaml(CONFIG_ROOT / "qwen35_4b_bf16_lora.yaml")
        versions = {name: "1.0.0" for name in REQUIRED_PACKAGES}
        gpu = {
            "available": True,
            "gpus": [
                {
                    "name": "NVIDIA GeForce RTX 5060 Ti",
                    "memory_total_mib": 16311,
                    "memory_used_mib": 1024,
                    "memory_free_mib": 15287,
                    "driver_version": "576.02",
                }
            ],
        }
        with (
            patch("training.llm.scripts.probe_environment.package_versions", return_value=versions),
            patch("training.llm.scripts.probe_environment.nvidia_smi", return_value=gpu),
            patch(
                "training.llm.scripts.probe_environment.pip_freeze_snapshot",
                return_value={
                    "status": "fail",
                    "command": [r"D:\environment\anaconda3\envs\meguri-llm\python.exe", "-m", "pip", "freeze"],
                    "error_type": "CalledProcessError",
                    "error": "pip freeze failed",
                },
            ),
        ):
            report = static_probe(config)
        self.assertEqual(report["status"], "fail")
        self.assertIn("pip freeze environment snapshot is unavailable", report["errors"])
        self.assertEqual(report["environment_lock"]["status"], "fail")

    def test_windows_compile_cache_uses_short_configured_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            "os.environ",
            {"MEGURI_LLM_COMPILE_CACHE_ROOT": directory},
            clear=False,
        ):
            with patch.dict(
                "os.environ",
                {"TORCHINDUCTOR_CACHE_DIR": "", "TRITON_CACHE_DIR": ""},
                clear=False,
            ):
                result = configure_compile_cache()
        self.assertTrue(result["configured"])
        self.assertEqual(Path(result["root"]), Path(directory).resolve())
        self.assertTrue(result["paths"]["TORCHINDUCTOR_CACHE_DIR"].endswith("torchinductor"))
        self.assertTrue(result["paths"]["TRITON_CACHE_DIR"].endswith("triton"))


if __name__ == "__main__":
    unittest.main()
