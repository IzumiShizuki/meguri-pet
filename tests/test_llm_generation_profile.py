from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import yaml

from training.llm.gateway.manager import RegistryModelManager
from training.llm.generation_profile import load_generation_profile, resolve_generation_settings
from training.llm.scripts.common import PipelineError, read_json, sha256_file
from training.llm.scripts.export_adapter import adapter_hash
from training.llm.scripts.register_model import register


ROOT = Path(__file__).resolve().parents[1]
MODEL_REVISION = "8" * 40
TOKENIZER_REVISION = "9" * 40


def profile_value(adapter_sha256: str, model_id: str) -> dict:
    return {
        "schema_version": 1,
        "profile_id": "decode-test-v2",
        "status": "validation_selected",
        "model": {
            "repo_id": "Qwen/Test",
            "revision": MODEL_REVISION,
            "tokenizer_revision": TOKENIZER_REVISION,
        },
        "adapter": {"model_id": model_id, "sha256": adapter_sha256},
        "generation": {
            "max_new_tokens": 256,
            "repetition_penalty": 1.05,
            "no_repeat_ngram_size": 4,
            "force_json_object_start": True,
        },
        "validation_evidence": {
            "run_id": "validation-test-v2",
            "report_sha256": "a" * 64,
            "validation_count": 566,
            "composite_score": 0.93,
            "json_parse_rate": 1.0,
            "response_schema_valid_rate": 1.0,
            "extra_field_rate": 0.0,
            "locked_eval_accessed": False,
        },
        "safety_evidence": {
            "run_id": "safety-test-v2",
            "report_sha256": "b" * 64,
            "passed": 8,
            "total": 8,
        },
        "policy": {
            "selection_scope": "validation_and_frozen_synthetic_safety_only",
            "staging_eligible": False,
            "production_ready": False,
            "requires_new_independently_frozen_locked_eval": True,
            "requires_frozen_rubric_human_review": True,
            "previous_locked_eval_suite_id": "meguri-locked-eval-v1",
            "previous_locked_eval_input_hashes_sha256": "1" * 64,
            "previous_locked_eval_must_not_be_reused_for_tuning": True,
        },
    }


class GenerationProfileTests(unittest.TestCase):
    def test_checked_in_v2_profile_is_schema_valid_and_not_staging_eligible(self) -> None:
        path = ROOT / "training/llm/configs/qwen35_4b_lora_decode_v2.yaml"
        profile = load_generation_profile(path)
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
        self.assertEqual(profile.profile_id, value["profile_id"])
        self.assertEqual(profile.sha256, sha256_file(path))
        self.assertFalse(value["policy"]["staging_eligible"])

    def test_profile_binds_training_config_adapter_and_generation_controls(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            adapter = root / "adapter"
            adapter.mkdir()
            (adapter / "adapter_model.safetensors").write_bytes(b"adapter")
            digest, _ = adapter_hash(adapter)
            model_id = "adapter-export-test"
            (adapter / "export_manifest.json").write_text(
                json.dumps({"model_id": model_id}), encoding="utf-8"
            )
            config = {
                "model": {
                    "repo_id": "Qwen/Test",
                    "revision": MODEL_REVISION,
                    "tokenizer_revision": TOKENIZER_REVISION,
                }
            }
            profile_path = root / "profile.yaml"
            profile_path.write_text(
                yaml.safe_dump(profile_value(digest, model_id), sort_keys=False),
                encoding="utf-8",
            )
            args = SimpleNamespace(
                generation_profile=profile_path,
                max_new_tokens=None,
                repetition_penalty=None,
                no_repeat_ngram_size=None,
                force_json_object_start=None,
            )
            settings, profile = resolve_generation_settings(
                args,
                training_config=config,
                adapter_path=adapter,
            )
            self.assertEqual(settings["repetition_penalty"], 1.05)
            self.assertEqual(settings["no_repeat_ngram_size"], 4)
            self.assertTrue(settings["force_json_object_start"])
            self.assertIsNotNone(profile)
            args.repetition_penalty = 1.1
            with self.assertRaisesRegex(PipelineError, "cannot be combined"):
                resolve_generation_settings(args, training_config=config, adapter_path=adapter)

    def test_default_generation_controls_remain_v1_compatible(self) -> None:
        args = SimpleNamespace(
            generation_profile=None,
            max_new_tokens=None,
            repetition_penalty=None,
            no_repeat_ngram_size=None,
            force_json_object_start=None,
        )
        settings, profile = resolve_generation_settings(
            args,
            training_config={"model": {}},
            adapter_path=None,
        )
        self.assertEqual(
            settings,
            {
                "max_new_tokens": 256,
                "repetition_penalty": 1.0,
                "no_repeat_ngram_size": 0,
                "force_json_object_start": False,
            },
        )
        self.assertIsNone(profile)

    def test_profile_bound_registration_uses_distinct_deployment_identity(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            adapter = root / "adapter"
            adapter.mkdir()
            (adapter / "adapter_model.safetensors").write_bytes(b"adapter")
            digest, _ = adapter_hash(adapter)
            export_model_id = "adapter-export-test"
            (adapter / "export_manifest.json").write_text(
                json.dumps(
                    {
                        "model_id": export_model_id,
                        "experiment_id": "experiment-test",
                        "adapter_sha256": digest,
                    }
                ),
                encoding="utf-8",
            )
            training_config = root / "training.yaml"
            training_config.write_text(
                yaml.safe_dump(
                    {
                        "model": {
                            "repo_id": "Qwen/Test",
                            "revision": MODEL_REVISION,
                            "tokenizer_revision": TOKENIZER_REVISION,
                        }
                    }
                ),
                encoding="utf-8",
            )
            profile_path = root / "profile.yaml"
            profile_path.write_text(
                yaml.safe_dump(profile_value(digest, export_model_id), sort_keys=False),
                encoding="utf-8",
            )
            profile = load_generation_profile(profile_path)
            experiment = root / "experiment.json"
            experiment.write_text(
                json.dumps(
                    {
                        "status": "pass",
                        "experiment_id": "experiment-test",
                        "base_model_repo": "Qwen/Test",
                        "base_model_revision": MODEL_REVISION,
                        "tokenizer_revision": TOKENIZER_REVISION,
                        "dataset_id": "dataset-test",
                        "data_build_id": "meguri_v2_02c3db0c507d7c2d",
                        "prompt_sha256": "c" * 64,
                        "response_schema_sha256": "d" * 64,
                        "chat_template_sha256": "e" * 64,
                        "training_commit": "f" * 40,
                        "framework_versions": {},
                        "training_config": str(training_config),
                    }
                ),
                encoding="utf-8",
            )
            selection = root / "selection.json"
            selection.write_text(
                json.dumps({"selected": {"adapter_sha256": digest}}), encoding="utf-8"
            )
            locked = root / "locked.json"
            locked.write_text(
                json.dumps(
                    {
                        "status": "pass",
                        "run_id": "locked-v2",
                        "counts": {"total": 184},
                        "model": {"adapter_path": str(adapter)},
                        "provenance": {
                            "generation_profile_id": profile.profile_id,
                            "generation_profile_sha256": profile.sha256,
                            "locked_eval_suite_id": "meguri-locked-eval-v2",
                            "locked_eval_manifest_sha256": "2" * 64,
                            "eval_input_hashes": {"jp": "new-jp", "zh": "new-zh"},
                        },
                    }
                ),
                encoding="utf-8",
            )
            comparison = root / "comparison.json"
            comparison.write_text(
                json.dumps(
                    {
                        "candidate": {"run_id": "locked-v2"},
                        "staging_gate": {"status": "pass"},
                        "provenance": {
                            "generation_profile_id": profile.profile_id,
                            "generation_profile_sha256": profile.sha256,
                            "locked_eval_suite_id": "meguri-locked-eval-v2",
                            "locked_eval_manifest_sha256": "2" * 64,
                        },
                    }
                ),
                encoding="utf-8",
            )
            registry = root / "registry.json"
            registry.write_text(json.dumps({"models": []}), encoding="utf-8")
            with patch("training.llm.scripts.register_model._validate_registry"):
                entry = register(
                    registry_path=registry,
                    schema_path=root / "schema.json",
                    export_dir=adapter,
                    experiment_path=experiment,
                    selection_path=selection,
                    locked_eval_path=locked,
                    comparison_path=comparison,
                    status="staging_candidate",
                    parent_model_id="parent",
                    rollback_model_id="last-good",
                    generation_profile_path=profile_path,
                    model_id="adapter-export-test-decode-v2",
                )
            self.assertEqual(entry["model_id"], "adapter-export-test-decode-v2")
            self.assertEqual(entry["generation_profile_sha256"], profile.sha256)
            self.assertEqual(entry["locked_eval_suite_id"], "meguri-locked-eval-v2")

    def test_gateway_readiness_fails_closed_on_profile_digest_drift(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            adapter = root / "adapter"
            adapter.mkdir()
            (adapter / "adapter_model.safetensors").write_bytes(b"adapter")
            digest, _ = adapter_hash(adapter)
            model_id = "adapter-export-test"
            (adapter / "export_manifest.json").write_text(
                json.dumps({"model_id": model_id}), encoding="utf-8"
            )
            config_path = root / "training.yaml"
            config_path.write_text(
                yaml.safe_dump(
                    {
                        "model": {
                            "repo_id": "Qwen/Test",
                            "revision": MODEL_REVISION,
                            "tokenizer_revision": TOKENIZER_REVISION,
                        }
                    }
                ),
                encoding="utf-8",
            )
            profile_path = root / "profile.yaml"
            profile_path.write_text(
                yaml.safe_dump(profile_value(digest, model_id), sort_keys=False),
                encoding="utf-8",
            )
            locked_path = root / "locked.json"
            locked = {
                "status": "pass",
                "run_id": "locked-v2",
                "counts": {"total": 184},
                "provenance": {
                    "generation_profile_id": "decode-test-v2",
                    "generation_profile_sha256": sha256_file(profile_path),
                    "locked_eval_suite_id": "meguri-locked-eval-v2",
                    "locked_eval_manifest_sha256": "2" * 64,
                },
            }
            locked_path.write_text(json.dumps(locked), encoding="utf-8")
            comparison_path = root / "comparison.json"
            comparison_path.write_text(
                json.dumps(
                    {
                        "candidate": {"run_id": "locked-v2"},
                        "staging_gate": {"status": "pass"},
                        "provenance": {
                            **locked["provenance"],
                            "candidate_report": sha256_file(locked_path),
                        },
                    }
                ),
                encoding="utf-8",
            )
            entry = {
                "model_id": "deployment-decode-v2",
                "status": "staging_candidate",
                "artifact_path": str(adapter),
                "adapter_sha256": digest,
                "base_model": "Qwen/Test",
                "base_revision": MODEL_REVISION,
                "tokenizer_revision": TOKENIZER_REVISION,
                "training_config": str(config_path),
                "generation_profile": str(profile_path),
                "generation_profile_id": "decode-test-v2",
                "generation_profile_sha256": sha256_file(profile_path),
                "locked_eval_suite_id": "meguri-locked-eval-v2",
                "locked_eval_manifest_sha256": "2" * 64,
                "locked_eval_report": str(locked_path),
                "comparison_report": str(comparison_path),
            }
            registry = root / "registry.json"
            routing = root / "routing.json"
            registry.write_text(json.dumps({"models": [entry]}), encoding="utf-8")
            routing.write_text(
                json.dumps(
                    {
                        "candidate_enabled": True,
                        "candidate_model_id": entry["model_id"],
                        "last_good_model_id": None,
                    }
                ),
                encoding="utf-8",
            )
            manager = RegistryModelManager(registry, routing)
            self.assertTrue(manager.readiness()["ready"])
            profile_path.write_text(profile_path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            readiness = manager.readiness()
            self.assertFalse(readiness["ready"])
            self.assertIn("runtime identity", readiness["issues"][0])

    def test_checked_in_registry_matches_schema(self) -> None:
        registry = read_json(ROOT / "training/llm/registry/model_registry.json")
        schema = read_json(ROOT / "training/llm/registry/model_registry.schema.json")
        required = set(schema["$defs"]["model"]["required"])
        for entry in registry["models"]:
            self.assertFalse(required - set(entry))
            self.assertIn("generation_profile_sha256", entry)


if __name__ == "__main__":
    unittest.main()
