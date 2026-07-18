from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from training.llm.scripts.common import PipelineError, sha256_text
from training.llm.scripts.training_utils import (
    deterministic_stratified_subset,
    token_normalized_causal_lm_loss,
    tokenize_assistant_only,
    validate_enablement_gate_report,
    validate_input_padding,
    validate_probe_report,
    validate_smoke_report,
    validate_training_peak_memory,
    validate_training_config,
)


class FakeTokenizer:
    eos_token_id = 0

    def apply_chat_template(self, messages, *, tokenize, add_generation_prompt, enable_thinking):
        self._validate_args(tokenize, enable_thinking)
        prefix = "".join(f"<{item['role']}>{item['content']}" for item in messages)
        if add_generation_prompt:
            return prefix + "<assistant>"
        return prefix + "\0"

    @staticmethod
    def _validate_args(tokenize, enable_thinking):
        if tokenize or enable_thinking:
            raise AssertionError("unexpected fake-tokenizer arguments")

    def __call__(self, value, *, add_special_tokens):
        if add_special_tokens:
            raise AssertionError("special tokens must be owned by the chat template")
        return {"input_ids": [ord(item) for item in value]}

    def decode(self, values, *, skip_special_tokens):
        if not skip_special_tokens:
            raise AssertionError("test expects special token removal")
        return "".join(chr(item) for item in values if item != self.eos_token_id)


class TrailingWhitespaceTokenizer(FakeTokenizer):
    def apply_chat_template(self, messages, *, tokenize, add_generation_prompt, enable_thinking):
        rendered = super().apply_chat_template(
            messages,
            tokenize=tokenize,
            add_generation_prompt=add_generation_prompt,
            enable_thinking=enable_thinking,
        )
        return rendered if add_generation_prompt else rendered + "\n"


def row(sample_id: str, language: str, stage: str, mode: str) -> dict:
    assistant = json.dumps(
        {
            "reply": "了解",
            "expression_tag": "neutral",
            "expression_intensity": "low",
            "voice_style": "neutral",
            "memory_candidates": [],
        },
        ensure_ascii=False,
    )
    return {
        "messages": [
            {"role": "system", "content": "system"},
            {"role": "user", "content": "user"},
            {"role": "assistant", "content": assistant},
        ],
        "metadata": {
            "sample_id": sample_id,
            "language": language,
            "relationship_stage": stage,
            "interaction_mode": mode,
        },
    }


def passing_probe_report() -> dict:
    packages = ["alpha==1.0", "beta==2.0"]
    return {
        "status": "pass",
        "mode": "full",
        "git_commit": "c" * 40,
        "model": {
            "repo_id": "Qwen/Qwen3.5-4B",
            "revision": "a" * 40,
            "tokenizer_revision": "a" * 40,
        },
        "static": {
            "status": "pass",
            "environment_lock": {
                "status": "pass",
                "line_count": len(packages),
                "sha256": sha256_text("\n".join(packages) + "\n"),
                "packages": packages,
            },
        },
        "full": {
            "status": "pass",
            "checks": {
                "cuda_available": True,
                "bf16_supported": True,
                "model_loaded": True,
                "assistant_mask": True,
                "forward": True,
                "backward": True,
                "gradient_checkpointing": True,
                "adapter_save": True,
                "adapter_reload": True,
                "json_inference": True,
            },
        },
    }


class TrainingUtilsTests(unittest.TestCase):
    def test_assistant_only_mask_keeps_complete_json_and_eos(self) -> None:
        encoded = tokenize_assistant_only(row("one", "zh", "sibling", "work"), FakeTokenizer(), max_seq_length=2048)
        first_supervised = next(index for index, value in enumerate(encoded["labels"]) if value != -100)
        self.assertTrue(all(value == -100 for value in encoded["labels"][:first_supervised]))
        self.assertEqual(encoded["input_ids"][-1], FakeTokenizer.eos_token_id)
        self.assertEqual(encoded["labels"][-1], FakeTokenizer.eos_token_id)

    def test_assistant_only_mask_allows_template_whitespace_after_eos(self) -> None:
        encoded = tokenize_assistant_only(
            row("one", "zh", "sibling", "work"),
            TrailingWhitespaceTokenizer(),
            max_seq_length=2048,
        )
        supervised = [value for value in encoded["labels"] if value != -100]
        self.assertIn(TrailingWhitespaceTokenizer.eos_token_id, supervised)
        self.assertEqual(supervised[-1], ord("\n"))

    def test_stratified_subset_is_reproducible(self) -> None:
        rows = [row(str(index), "ja" if index % 2 else "zh", "sibling", "work") for index in range(20)]
        first = deterministic_stratified_subset(rows, size=10, seed=3407)
        second = deterministic_stratified_subset(rows, size=10, seed=3407)
        self.assertEqual(
            [item["metadata"]["sample_id"] for item in first],
            [item["metadata"]["sample_id"] for item in second],
        )

    def test_qwen35_quantization_is_rejected(self) -> None:
        config = {
            "schema_version": 1,
            "experiment_family": "test",
            "enabled": True,
            "model": {
                "repo_id": "Qwen/Qwen3.5-4B",
                "revision": "a",
                "tokenizer_repo_id": "Qwen/Qwen3.5-4B",
                "tokenizer_revision": "a",
                "loader": "unsloth_vision",
                "train_vision_layers": False,
                "load_in_4bit": True,
            },
            "training": {
                "assistant_only_loss": True,
                "max_seq_length": 2048,
                "per_device_train_batch_size": 1,
            },
            "lora": {},
            "hardware": {},
        }
        with self.assertRaises(PipelineError):
            validate_training_config(config)

    def test_disabled_route_requires_gate_report(self) -> None:
        config = {
            "enabled": False,
            "enablement_gates": ["four_b_pipeline_stable", "project_lead_approved_training_time"],
        }
        with self.assertRaises(PipelineError):
            validate_enablement_gate_report(None, config)

    def test_probe_report_requires_verified_environment_lock(self) -> None:
        config = {"model": passing_probe_report()["model"]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "probe.json"
            report = passing_probe_report()
            report["static"]["environment_lock"] = {"status": "pass"}
            path.write_text(json.dumps(report), encoding="utf-8")
            with self.assertRaisesRegex(PipelineError, "complete pip freeze environment lock"):
                validate_probe_report(path, config)

    def test_probe_report_requires_pinned_git_commit(self) -> None:
        config = {"model": passing_probe_report()["model"]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "probe.json"
            report = passing_probe_report()
            report["git_commit"] = "unknown"
            path.write_text(json.dumps(report), encoding="utf-8")
            with self.assertRaisesRegex(PipelineError, "pinned Git commit"):
                validate_probe_report(path, config)

    def test_probe_report_rejects_tampered_environment_lock(self) -> None:
        config = {"model": passing_probe_report()["model"]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "probe.json"
            report = passing_probe_report()
            report["static"]["environment_lock"]["packages"].append("gamma==3.0")
            report["static"]["environment_lock"]["line_count"] = 3
            path.write_text(json.dumps(report), encoding="utf-8")
            with self.assertRaisesRegex(PipelineError, "environment lock hash is invalid"):
                validate_probe_report(path, config)

    def test_smoke_padding_contract_accepts_observed_subset(self) -> None:
        result = validate_input_padding(
            input_pad_length=768,
            max_seq_length=2048,
            train_lengths=[652, 708, 747],
            validation_lengths=[652, 733, 755],
            required=True,
        )
        self.assertTrue(result["enabled"])
        self.assertEqual(result["input_pad_length"], 768)
        self.assertEqual(result["validation_max_tokens"], 755)

    def test_smoke_padding_contract_rejects_variable_shapes(self) -> None:
        with self.assertRaisesRegex(PipelineError, "requires a fixed"):
            validate_input_padding(
                input_pad_length=None,
                max_seq_length=2048,
                train_lengths=[700],
                validation_lengths=[710],
                required=True,
            )

    def test_padding_contract_rejects_too_short_length(self) -> None:
        with self.assertRaisesRegex(PipelineError, "exceeds fixed pad length"):
            validate_input_padding(
                input_pad_length=736,
                max_seq_length=2048,
                train_lengths=[747],
                validation_lengths=[755],
                required=True,
            )

    def test_token_normalized_loss_uses_accumulated_item_count(self) -> None:
        calls = []

        class FakeLogits:
            shape = (1, 8, 17)

        def fake_loss(logits, labels, *, vocab_size, num_items_in_batch):
            calls.append((logits, labels, vocab_size, num_items_in_batch))
            return "normalized-loss"

        outputs = SimpleNamespace(logits=FakeLogits())
        labels = object()
        result = token_normalized_causal_lm_loss(
            outputs,
            labels,
            num_items_in_batch=123,
            loss_function=fake_loss,
        )
        self.assertEqual(result, "normalized-loss")
        self.assertEqual(calls, [(outputs.logits, labels, 17, 123)])

    def test_token_normalized_loss_counts_current_evaluation_batch(self) -> None:
        calls = []

        class Count:
            def sum(self):
                return 7

        class Labels:
            def ne(self, ignored):
                self.ignored = ignored
                return Count()

        def fake_loss(logits, labels, *, vocab_size, num_items_in_batch):
            calls.append((vocab_size, num_items_in_batch, labels.ignored))
            return "evaluation-loss"

        outputs = SimpleNamespace(logits=SimpleNamespace(shape=(1, 8, 17)))
        labels = Labels()
        result = token_normalized_causal_lm_loss(
            outputs,
            labels,
            num_items_in_batch=None,
            loss_function=fake_loss,
        )
        self.assertEqual(result, "evaluation-loss")
        self.assertEqual(calls, [(17, 7, -100)])

    def test_smoke_report_gate_accepts_complete_l1_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adapter = root / "adapter"
            adapter.mkdir()
            (adapter / "adapter_model.safetensors").write_bytes(b"adapter")
            config = {
                "model": {
                    "repo_id": "Qwen/Qwen3.5-4B",
                    "revision": "a" * 40,
                    "tokenizer_revision": "a" * 40,
                },
                "hardware": {"maximum_training_peak_gib": 14.5},
            }
            dataset = {"dataset_id": "dataset-1", "source_build_id": "build-1"}
            report = {
                "status": "pass",
                "stage": "L1_smoke",
                "training_commit": "b" * 40,
                "base_model_repo": "Qwen/Qwen3.5-4B",
                "base_model_revision": "a" * 40,
                "tokenizer_revision": "a" * 40,
                "dataset_id": "dataset-1",
                "data_build_id": "build-1",
                "training_config_sha256": "config-hash",
                "train_samples": 160,
                "training_parameters": {"max_steps": 75},
                "locked_eval_accessed": False,
                "post_training_json_smoke": {"status": "pass"},
                "loss_normalization": "assistant_tokens_across_gradient_accumulation",
                "input_padding": {"enabled": True, "input_pad_length": 768},
                "peak_vram_bytes": 11 * (1024**3),
                "final_adapter": str(adapter),
            }
            path = root / "smoke.json"
            path.write_text(json.dumps(report), encoding="utf-8")
            validated = validate_smoke_report(
                path,
                config=config,
                dataset_manifest=dataset,
                training_config_sha256="config-hash",
            )
            self.assertEqual(validated["stage"], "L1_smoke")

    def test_smoke_report_gate_rejects_locked_eval_access(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "smoke.json"
            path.write_text(json.dumps({"status": "pass", "stage": "L1_smoke"}), encoding="utf-8")
            config = {
                "model": {"repo_id": "x", "revision": "a" * 40, "tokenizer_revision": "a" * 40},
                "hardware": {"maximum_training_peak_gib": 14.5},
            }
            with self.assertRaises(PipelineError):
                validate_smoke_report(
                    path,
                    config=config,
                    dataset_manifest={"dataset_id": "d", "source_build_id": "b"},
                    training_config_sha256="hash",
                )

    def test_peak_memory_gate_fails_closed(self) -> None:
        config = {"hardware": {"maximum_training_peak_gib": 14.5}}
        validate_training_peak_memory(14 * (1024**3), config)
        with self.assertRaisesRegex(PipelineError, "exceeds configured limit"):
            validate_training_peak_memory(15 * (1024**3), config)


if __name__ == "__main__":
    unittest.main()
