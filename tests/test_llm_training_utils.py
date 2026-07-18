from __future__ import annotations

import json
import unittest

from training.llm.scripts.common import PipelineError
from training.llm.scripts.training_utils import (
    deterministic_stratified_subset,
    tokenize_assistant_only,
    validate_enablement_gate_report,
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


class TrainingUtilsTests(unittest.TestCase):
    def test_assistant_only_mask_keeps_complete_json_and_eos(self) -> None:
        encoded = tokenize_assistant_only(row("one", "zh", "sibling", "work"), FakeTokenizer(), max_seq_length=2048)
        first_supervised = next(index for index, value in enumerate(encoded["labels"]) if value != -100)
        self.assertTrue(all(value == -100 for value in encoded["labels"][:first_supervised]))
        self.assertEqual(encoded["input_ids"][-1], FakeTokenizer.eos_token_id)
        self.assertEqual(encoded["labels"][-1], FakeTokenizer.eos_token_id)

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


if __name__ == "__main__":
    unittest.main()
