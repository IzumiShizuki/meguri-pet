from __future__ import annotations

import json
import unittest
from subprocess import CompletedProcess
from unittest.mock import patch

from training.llm.eval.persona_eval import evaluate_persona
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


if __name__ == "__main__":
    unittest.main()
