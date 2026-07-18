from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from ops.scripts.check_release_manifest import validate_value
from training.llm.eval.eval_cases import frozen_prompt_contract, load_locked_cases
from training.llm.eval.locked_suite import (
    build_independent_manifest,
    validate_independent_manifest,
)
from training.llm.scripts.common import PipelineError, canonical_json, sha256_file


class LockedSuiteTests(unittest.TestCase):
    def test_checked_in_request_exposes_no_cases_and_binds_profile(self) -> None:
        root = Path(__file__).resolve().parents[1]
        request = json.loads(
            (root / "reports/text_llm_independent_locked_suite_request.json").read_text(
                encoding="utf-8"
            )
        )
        profile_sha256 = sha256_file(
            root / "training/llm/configs/qwen35_4b_lora_decode_v2.yaml"
        )
        self.assertEqual(request["status"], "WAITING_INDEPENDENT_FREEZE")
        self.assertEqual(request["candidate"]["generation_profile_sha256"], profile_sha256)
        self.assertFalse(request["manifest_builder"]["raw_case_text_written_to_manifest"])
        self.assertFalse(request["staging_eligible"])

    def write_eval(
        self,
        root: Path,
        *,
        source_build_id: str,
        prefix: str,
        overlap_sample_id: str | None = None,
    ) -> None:
        root.mkdir()
        for language in ("jp", "zh"):
            rows = []
            for index in range(92):
                sample_id = f"{prefix}-{language}-{index:03d}"
                if language == "jp" and index == 0 and overlap_sample_id is not None:
                    sample_id = overlap_sample_id
                rows.append(
                    {
                        "build_id": source_build_id,
                        "sample_id": sample_id,
                        "language": language,
                        "messages": [
                            {
                                "role": "user",
                                "content": (
                                    f"legacy archived weather dialogue {language} {index}"
                                    if prefix == "old"
                                    else f"novel heldout cooking scenario {language} {index}"
                                ),
                            }
                        ],
                        "metadata": {
                            "split": "test",
                            "scene_id": f"{prefix}-scene-{language}-{index:03d}",
                            "relationship_stage": "sibling",
                            "outfit_code": "01",
                        },
                    }
                )
            (root / f"cases_{language}.jsonl").write_text(
                "".join(canonical_json(row) + "\n" for row in rows),
                encoding="utf-8",
            )

    def write_dataset(self, root: Path) -> Path:
        root.mkdir()
        rows = []
        for language in ("ja", "zh"):
            rows.append(
                {
                    "messages": [
                        {"role": "system", "content": "system"},
                        {"role": "user", "content": f"training {language} message"},
                        {"role": "assistant", "content": "{}"},
                    ],
                    "metadata": {
                        "sample_id": f"train-{language}",
                        "language": language,
                        "scene_id": f"train-scene-{language}",
                        "relationship_stage": "sibling",
                        "outfit_code": "01",
                    },
                }
            )
        train = root / "train.jsonl"
        validation = root / "validation.jsonl"
        train.write_text(canonical_json(rows[0]) + "\n", encoding="utf-8")
        validation.write_text(canonical_json(rows[1]) + "\n", encoding="utf-8")
        (root / "dataset_manifest.json").write_text(
            json.dumps(
                {
                    "dataset_id": "dataset-test",
                    "files": {
                        "train.jsonl": sha256_file(train),
                        "validation.jsonl": sha256_file(validation),
                    },
                }
            ),
            encoding="utf-8",
        )
        return root

    def previous_manifest(self, root: Path, eval_root: Path, rag: Path) -> Path:
        _, input_hashes = load_locked_cases(
            eval_root,
            expected_source_build_id="old-build",
        )
        _, _, hashes = frozen_prompt_contract()
        path = root / "previous-manifest.json"
        path.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "suite_id": "meguri-locked-eval-v1",
                    "source_build_id": "old-build",
                    "counts": {"jp": 92, "zh": 92, "total": 184},
                    "input_hashes": input_hashes,
                    "frozen_prompt_sha256": hashes["prompt_sha256"],
                    "response_schema_sha256": hashes["response_schema_sha256"],
                    "rag_train_sha256": sha256_file(rag),
                    "policy": {
                        "evaluation_only": True,
                        "training": False,
                        "prompt_tuning": False,
                        "early_stopping": False,
                        "checkpoint_selection": False,
                    },
                }
            ),
            encoding="utf-8",
        )
        return path

    def setup_inputs(self, root: Path, *, overlap_sample_id: str | None = None):
        previous_eval = root / "previous-eval"
        candidate_eval = root / "candidate-eval"
        self.write_eval(previous_eval, source_build_id="old-build", prefix="old")
        self.write_eval(
            candidate_eval,
            source_build_id="new-build",
            prefix="new",
            overlap_sample_id=overlap_sample_id,
        )
        dataset = self.write_dataset(root / "dataset")
        rag = root / "rag.jsonl"
        rag.write_text('{"text":"rag"}\n', encoding="utf-8")
        previous_manifest = self.previous_manifest(root, previous_eval, rag)
        return previous_eval, candidate_eval, dataset, rag, previous_manifest

    def test_v2_manifest_proves_disjoint_identity_and_revalidates(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            previous_eval, candidate_eval, dataset, rag, previous_manifest = self.setup_inputs(root)
            manifest = build_independent_manifest(
                suite_id="meguri-locked-eval-v2",
                source_build_id="new-build",
                eval_root=candidate_eval,
                dataset_dir=dataset,
                previous_manifest_path=previous_manifest,
                previous_eval_root=previous_eval,
                rag_jsonl=rag,
                prepared_by="independent-preparer",
                approved_by="independent-approver",
                source_authority="independent-heldout-v2",
                code_commit="a" * 40,
            )
            cases, input_hashes = load_locked_cases(
                candidate_eval,
                expected_source_build_id="new-build",
            )
            result = validate_independent_manifest(
                manifest,
                cases=cases,
                input_hashes=input_hashes,
                dataset_dir=dataset,
                previous_manifest_path=previous_manifest,
                previous_eval_root=previous_eval,
                rag_jsonl=rag,
            )
            self.assertEqual(result["status"], "pass")
            schema = json.loads(
                (
                    Path(__file__).resolve().parents[1]
                    / "training/llm/eval/fixtures/locked_eval_manifest_v2.schema.json"
                ).read_text(encoding="utf-8")
            )
            self.assertEqual(validate_value(manifest, schema, schema, "manifest"), [])
            self.assertEqual(result["case_identity"]["unique_sample_ids"], 184)
            self.assertTrue(all(value == 0 for value in result["isolation_checks"].values()))

    def test_builder_rejects_train_validation_sample_overlap(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            previous_eval, candidate_eval, dataset, rag, previous_manifest = self.setup_inputs(
                root,
                overlap_sample_id="train-ja",
            )
            with self.assertRaisesRegex(PipelineError, "train_validation_sample_id_overlap"):
                build_independent_manifest(
                    suite_id="meguri-locked-eval-v2",
                    source_build_id="new-build",
                    eval_root=candidate_eval,
                    dataset_dir=dataset,
                    previous_manifest_path=previous_manifest,
                    previous_eval_root=previous_eval,
                    rag_jsonl=rag,
                    prepared_by="independent-preparer",
                    approved_by="independent-approver",
                    source_authority="independent-heldout-v2",
                    code_commit="a" * 40,
                )

    def test_builder_rejects_near_duplicate_of_previous_locked_input(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            previous_eval, candidate_eval, dataset, rag, previous_manifest = self.setup_inputs(root)
            jp_path = candidate_eval / "cases_jp.jsonl"
            rows = [json.loads(line) for line in jp_path.read_text(encoding="utf-8").splitlines()]
            rows[0]["messages"][0]["content"] = "legacy archived weather dialogue jp 0!"
            jp_path.write_text(
                "".join(canonical_json(row) + "\n" for row in rows),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(PipelineError, "previous_locked_near_input_overlap"):
                build_independent_manifest(
                    suite_id="meguri-locked-eval-v2",
                    source_build_id="new-build",
                    eval_root=candidate_eval,
                    dataset_dir=dataset,
                    previous_manifest_path=previous_manifest,
                    previous_eval_root=previous_eval,
                    rag_jsonl=rag,
                    prepared_by="independent-preparer",
                    approved_by="independent-approver",
                    source_authority="independent-heldout-v2",
                    code_commit="a" * 40,
                )

    def test_builder_rejects_same_source_build_or_same_approver(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            previous_eval, candidate_eval, dataset, rag, previous_manifest = self.setup_inputs(root)
            with self.assertRaisesRegex(PipelineError, "new source build"):
                build_independent_manifest(
                    suite_id="meguri-locked-eval-v2",
                    source_build_id="old-build",
                    eval_root=candidate_eval,
                    dataset_dir=dataset,
                    previous_manifest_path=previous_manifest,
                    previous_eval_root=previous_eval,
                    rag_jsonl=rag,
                    prepared_by="one",
                    approved_by="two",
                    source_authority="authority",
                    code_commit="a" * 40,
                )
            with self.assertRaisesRegex(PipelineError, "distinct preparer"):
                build_independent_manifest(
                    suite_id="meguri-locked-eval-v2",
                    source_build_id="new-build",
                    eval_root=candidate_eval,
                    dataset_dir=dataset,
                    previous_manifest_path=previous_manifest,
                    previous_eval_root=previous_eval,
                    rag_jsonl=rag,
                    prepared_by="same",
                    approved_by="same",
                    source_authority="authority",
                    code_commit="a" * 40,
                )


if __name__ == "__main__":
    unittest.main()
