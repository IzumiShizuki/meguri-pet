from __future__ import annotations

import json
import unittest
from subprocess import CompletedProcess
from types import SimpleNamespace
from unittest.mock import patch

from training.llm.eval.backends import (
    complete_json_object_end,
    json_object_start_token_id,
    validate_generation_controls,
)
from training.llm.eval.persona_eval import evaluate_persona
from training.llm.eval.run_validation_eval import run as run_validation_eval
from training.llm.eval.schema_eval import aggregate_schema_metrics, evaluate_output
from training.llm.scripts.common import PipelineError, require_clean_git_worktree


def valid_output(reply: str = "おかえりなさい、兄さん") -> str:
    return json.dumps(
        {
            "reply": reply,
            "expression_tag": "neutral",
            "expression_intensity": "low",
            "voice_style": "restrained",
            "memory_candidates": [],
        },
        ensure_ascii=False,
    )


class LlmEvalTests(unittest.TestCase):
    def setUp(self) -> None:
        self.expected = {
            "language": "jp",
            "relationship_stage": "sibling",
            "interaction_mode": "work",
            "expression_tag": "neutral",
            "expression_intensity": "low",
            "voice_style": "restrained",
        }

    def test_valid_contract_metrics(self) -> None:
        metrics = evaluate_output(valid_output(), self.expected)
        self.assertFalse(metrics["schema_error"])
        self.assertTrue(metrics["language_match"])
        report = aggregate_schema_metrics([{"metrics": metrics}])
        self.assertEqual(report["response_schema_valid_rate"], 1.0)

    def test_markdown_and_extra_fields_are_reported(self) -> None:
        payload = json.loads(valid_output())
        payload["extra"] = True
        metrics = evaluate_output("```json\n" + json.dumps(payload) + "\n```", self.expected)
        self.assertTrue(metrics["parse_error"])
        self.assertTrue(metrics["markdown_wrapped"])

    def test_relationship_escalation_is_flagged(self) -> None:
        raw = valid_output("兄さん、今すぐ結婚しましょう")
        metrics = evaluate_persona(raw, self.expected)
        self.assertFalse(metrics["relationship_severe_error_free_heuristic"])

    def test_versioned_artifacts_require_a_clean_git_worktree(self) -> None:
        results = [
            CompletedProcess(["git", "rev-parse", "HEAD"], 0, stdout="a" * 40 + "\n"),
            CompletedProcess(["git", "status"], 0, stdout=" M training/llm/eval/run_locked_eval.py\n"),
        ]
        with patch("training.llm.scripts.common.subprocess.run", side_effect=results):
            with self.assertRaisesRegex(PipelineError, "dirty Git worktree"):
                require_clean_git_worktree()

    def test_clean_git_worktree_returns_pinned_commit(self) -> None:
        commit = "b" * 40
        results = [
            CompletedProcess(["git", "rev-parse", "HEAD"], 0, stdout=commit + "\n"),
            CompletedProcess(["git", "status"], 0, stdout=""),
        ]
        with patch("training.llm.scripts.common.subprocess.run", side_effect=results):
            self.assertEqual(require_clean_git_worktree(), commit)

    def test_validation_progress_interval_must_be_positive(self) -> None:
        args = SimpleNamespace(run_id="validation-test", progress_every=0)
        with self.assertRaisesRegex(PipelineError, "progress interval must be positive"):
            run_validation_eval(args)

    def test_json_completion_ignores_braces_inside_strings(self) -> None:
        raw = '{"reply":"brace } and escaped \\\"{ text","memory_candidates":[]}\nuser'
        end = complete_json_object_end(raw)
        self.assertEqual(raw[:end], raw.split("\nuser", 1)[0])

    def test_json_completion_rejects_incomplete_object(self) -> None:
        self.assertIsNone(complete_json_object_end('{"reply":"unfinished"'))

    def test_json_completion_preserves_invalid_prefix(self) -> None:
        raw = '```json\n{"reply":"ok","memory_candidates":[]}\n```'
        end = complete_json_object_end(raw)
        self.assertEqual(raw[:end], '```json\n{"reply":"ok","memory_candidates":[]}')

    def test_generation_controls_are_bounded(self) -> None:
        self.assertEqual(validate_generation_controls(1.05, 4), (1.05, 4))
        with self.assertRaisesRegex(PipelineError, "repetition penalty"):
            validate_generation_controls(0.99, 4)
        with self.assertRaisesRegex(PipelineError, "no-repeat ngram"):
            validate_generation_controls(1.05, 33)

    def test_json_object_start_must_be_one_token(self) -> None:
        class Tokenizer:
            def __init__(self, token_ids):
                self.token_ids = token_ids

            def encode(self, value, *, add_special_tokens):
                self.asserted = (value, add_special_tokens)
                return self.token_ids

        tokenizer = Tokenizer([42])
        self.assertEqual(json_object_start_token_id(tokenizer), 42)
        self.assertEqual(tokenizer.asserted, ("{", False))
        with self.assertRaisesRegex(PipelineError, "one token"):
            json_object_start_token_id(Tokenizer([4, 2]))


if __name__ == "__main__":
    unittest.main()
