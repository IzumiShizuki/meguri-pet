from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from training.llm.eval.human_review import finalize_review, prepare_review_packet
from training.llm.scripts.common import PipelineError, canonical_json, read_json, sha256_file


class HumanReviewTests(unittest.TestCase):
    def locked_eval(self, root: Path) -> Path:
        run = root / "locked"
        run.mkdir()
        raw_path = run / "raw_outputs.jsonl"
        rows = []
        for index in range(184):
            rows.append(
                {
                    "sequence": index + 1,
                    "case_fingerprint": f"fingerprint-{index:03d}",
                    "expected": {
                        "language": "jp" if index < 92 else "zh",
                        "relationship_stage": "sibling",
                        "interaction_mode": "private",
                    },
                    "raw_output": canonical_json(
                        {
                            "reply": "了解しました" if index < 92 else "知道了",
                            "expression_tag": "neutral",
                            "expression_intensity": "low",
                            "voice_style": "neutral",
                            "memory_candidates": [],
                        }
                    ),
                }
            )
        raw_path.write_text(
            "".join(canonical_json(row) + "\n" for row in rows), encoding="utf-8"
        )
        report = {
            "schema_version": 1,
            "run_id": "candidate-new-locked-v2",
            "status": "pass",
            "counts": {"total": 184, "jp": 92, "zh": 92},
            "locked_eval_policy": {
                "evaluation_only": True,
                "used_for_checkpoint_selection": False,
            },
            "independent_suite_validation": {
                "status": "pass",
                "suite_id": "meguri-locked-eval-v2",
            },
            "provenance": {
                "raw_outputs_sha256": sha256_file(raw_path),
                "generation_profile_id": "decode-v2",
                "generation_profile_sha256": "a" * 64,
                "locked_eval_suite_id": "meguri-locked-eval-v2",
                "locked_eval_source_build_id": "new-eval-build-v2",
                "locked_eval_manifest_sha256": "d" * 64,
            },
        }
        (run / "report.json").write_text(json.dumps(report), encoding="utf-8")
        return run

    def test_complete_frozen_review_is_recomputed_and_approved(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            run = self.locked_eval(root)
            packet = root / "packet.json"
            template = root / "review.json"
            prepare_review_packet(run, packet, template, code_commit="b" * 40)
            completed = read_json(template)
            completed["reviewer"] = {
                "reviewer_id": "reviewer-01",
                "reviewed_at": "2026-07-16T08:00:00+08:00",
                "independent_of_training_and_prompt_tuning": True,
                "locked_eval_content_not_used_for_tuning": True,
            }
            for item in completed["items"]:
                item["ratings"] = {field: True for field in item["ratings"]}
            template.write_text(json.dumps(completed), encoding="utf-8")
            output = root / "result.json"
            result = finalize_review(packet, template, output, code_commit="c" * 40)
            self.assertTrue(result["approved"])
            self.assertEqual(result["persona_score"], 1.0)
            self.assertEqual(result["component_rates"]["safety_acceptable"], 1.0)
            self.assertEqual(result["counts"]["rated"], 184)

    def test_review_requires_independence_and_non_tuning_declarations(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            run = self.locked_eval(root)
            packet = root / "packet.json"
            template = root / "review.json"
            prepare_review_packet(run, packet, template, code_commit="b" * 40)
            completed = read_json(template)
            completed["reviewer"] = {
                "reviewer_id": "reviewer-01",
                "reviewed_at": "2026-07-16T08:00:00+08:00",
                "independent_of_training_and_prompt_tuning": False,
                "locked_eval_content_not_used_for_tuning": True,
            }
            for item in completed["items"]:
                item["ratings"] = {field: True for field in item["ratings"]}
            template.write_text(json.dumps(completed), encoding="utf-8")
            with self.assertRaisesRegex(PipelineError, "independence declaration"):
                finalize_review(packet, template, root / "result.json", code_commit="c" * 40)

    def test_each_language_must_meet_the_naturalness_gate(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            run = self.locked_eval(root)
            packet = root / "packet.json"
            template = root / "review.json"
            prepare_review_packet(run, packet, template, code_commit="b" * 40)
            completed = read_json(template)
            completed["reviewer"] = {
                "reviewer_id": "reviewer-01",
                "reviewed_at": "2026-07-16T08:00:00+08:00",
                "independent_of_training_and_prompt_tuning": True,
                "locked_eval_content_not_used_for_tuning": True,
            }
            for item in completed["items"]:
                item["ratings"] = {field: True for field in item["ratings"]}
            for item in completed["items"][:10]:
                item["ratings"]["language_natural"] = False
            template.write_text(json.dumps(completed), encoding="utf-8")
            result = finalize_review(
                packet,
                template,
                root / "result.json",
                code_commit="c" * 40,
            )
            self.assertGreater(result["persona_score"], 0.90)
            self.assertLess(result["language_naturalness_by_language"]["jp"], 0.90)
            self.assertFalse(result["approved"])


if __name__ == "__main__":
    unittest.main()
