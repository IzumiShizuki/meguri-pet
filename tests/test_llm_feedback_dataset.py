from __future__ import annotations

import copy
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from training.llm.scripts.common import (
    PipelineError, SOURCE_BUILD_ID, canonical_json, read_json, read_jsonl,
    sha256_file, sha256_text, write_json, write_jsonl,
)
from training.llm.scripts.dataset import _response_contract, _runtime_block
from training.llm.scripts.feedback import (
    DATASET_KIND, _sample_key, build_feedback_dataset, import_feedback,
    record_review, validate_feedback_dataset,
)
from training.llm.scripts.feedback_dataset import parser
from training.llm.scripts.training_utils import tokenize_assistant_only, validate_dataset_for_training
from tests.test_llm_training_utils import FakeTokenizer


RUNTIME = {"language": "zh", "relationship_stage": "sibling", "outfit_code": "01", "interaction_mode": "work"}


def response(reply: str) -> dict:
    return {"reply": reply, "expression_tag": "neutral", "expression_intensity": "low",
            "voice_style": "neutral", "memory_candidates": []}


def source_row(feedback_id: str, *, session: str = "conversation-one", scene: str | None = None) -> dict:
    row = {"schema_version": "meguri-behavior-feedback-v2", "feedback_id": feedback_id,
        "captured_at": "2026-10-08T00:00:00Z", "status": "training_candidate",
        "consent_to_training": True, "consent_version": "meguri-training-consent-v1",
        "consent_captured_at": "2026-10-08T00:00:00Z", "training_mode": True,
        "tenant_id": "meguri-local", "user_id": "local-user", "client_id": "desktop",
        "session_id": session, "turn_id": "turn-" + feedback_id,
        "user_message": "请给我建议 " + feedback_id, "recent_context": [],
        "selected_candidate_id": feedback_id + "-a", "rejected_candidate_ids": [feedback_id + "-b"],
        "feedback_text": "", "relationship_profile": "sibling", "reply_format": "default",
        "candidates": [{"candidate_id": feedback_id + "-a", "response": response("好呀 " + feedback_id)},
                       {"candidate_id": feedback_id + "-b", "response": response("另一种回复 " + feedback_id)}],
        "provenance": {"source": "desktop_active_learning", "model_provider": "test-provider",
                       "build_id": "runtime-build", "comparison_id": "comparison-" + feedback_id}}
    if scene:
        row["scene_id"] = scene
    return row


class FeedbackDatasetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.baseline_temp = tempfile.TemporaryDirectory()
        cls.baseline = Path(cls.baseline_temp.name) / "approved-baseline"
        cls.baseline.mkdir()
        prompt = "Pinned approved Meguri training prompt"
        block, _ = _runtime_block(RUNTIME, _response_contract()[2])
        for split, count in (("train", 2626), ("validation", 566)):
            rows = [{"messages": [{"role": "system", "content": prompt + "\n\n" + block},
                        {"role": "user", "content": f"baseline-{split}-{index}"},
                        {"role": "assistant", "content": canonical_json(response(f"baseline reply {split} {index}"))}],
                     "metadata": {**RUNTIME, "sample_id": f"{split}-{index}", "scene_id": split + "-scene",
                                  "data_build_id": SOURCE_BUILD_ID}}
                    for index in range(count)]
            write_jsonl(cls.baseline / f"{split}.jsonl", rows)
        write_json(cls.baseline / "quality_report.json", {"status": "pass"})
        manifest = {"schema_version": 1, "dataset_id": "test-approved-baseline",
            "source_build_id": SOURCE_BUILD_ID, "train_count": 2626, "validation_count": 566,
            "locked_eval_count": 184, "input_hashes": {"approved-export": "a" * 64},
            "split_metadata_sha256": "b" * 64, "prompt_sha256": sha256_text(prompt),
            "response_schema_sha256": _response_contract()[1], "quality_gate_status": "pass",
            "locked_eval_policy": {"content_read_by_converter": False, "used_for_training": False, "used_for_prompt_tuning": False},
            "files": {name: sha256_file(cls.baseline / name) for name in ("train.jsonl", "validation.jsonl", "quality_report.json")}}
        write_json(cls.baseline / "dataset_manifest.json", manifest)
        cls.anchor_patch = patch("training.llm.scripts.feedback.BASELINE_ANCHOR", cls.baseline / "dataset_manifest.json")
        cls.anchor_patch.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.anchor_patch.stop()
        cls.baseline_temp.cleanup()

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source" / "feedback.jsonl"
        self.ledger = self.root / "reviews" / "review.jsonl"
        self.splits = self.root / "split-metadata"
        self.splits.mkdir()
        (self.splits / "test_scene_ids.txt").write_text("locked-scene\n", encoding="utf-8")

    def imported(self, rows: list[dict], **kwargs) -> tuple[Path, list[dict]]:
        write_jsonl(self.source, rows)
        output = import_feedback([self.source], self.root / "imports", **kwargs)
        return output, [row for _, row in read_jsonl(output / "pending.jsonl")]

    def approve(self, imported: Path, rows: list[dict], *, overrides: dict | None = None) -> None:
        for row in rows:
            record_review(imported, self.ledger, feedback_id=row["feedback_id"],
                candidate_digest=row["candidate_digest"], decision="approve", reviewer="human-reviewer",
                privacy_reviewed=True, runtime=RUNTIME, **(overrides or {}))

    def build(self, imported: Path, **kwargs) -> Path:
        if not self.ledger.exists():
            self.ledger.parent.mkdir(parents=True)
            self.ledger.touch()
        return build_feedback_dataset(import_dir=imported, ledger=self.ledger, baseline_dir=self.baseline,
            split_root=self.splits, output_root=self.root / "outputs", **kwargs)

    def feedback_rows(self, output: Path) -> list[dict]:
        manifest = read_json(output / "dataset_manifest.json")
        return [row for split in ("train", "validation")
                for _, row in list(read_jsonl(output / f"{split}.jsonl"))[manifest["baseline_counts"][split]:]]

    def test_import_requires_explicit_consent_and_known_provenance(self) -> None:
        rows = []
        for index, consent in enumerate((None, False, "true")):
            row = source_row(f"missing-{index}")
            row["consent_to_training"] = consent
            rows.append(row)
        unknown = source_row("unknown")
        unknown["provenance"]["source"] = "unknown"
        rows.append(unknown)
        imported, pending = self.imported(rows)
        self.assertEqual(pending, [])
        self.assertFalse(read_json(imported / "import_manifest.json")["auto_approved"])

    def test_legacy_requires_explicit_operator_opt_in_and_service_shape(self) -> None:
        legacy = source_row("legacy")
        legacy["schema_version"] = "meguri-behavior-feedback-v1"
        del legacy["consent_to_training"]
        write_jsonl(self.source, [legacy])
        first = import_feedback([self.source], self.root / "imports")
        self.assertEqual(list(read_jsonl(first / "pending.jsonl")), [])
        second = import_feedback([self.source], self.root / "legacy-imports", allow_legacy_service_provenance=True)
        row = list(read_jsonl(second / "pending.jsonl"))[0][1]
        self.assertEqual(row["status"], "pending_review")
        self.assertEqual(row["consent_basis"], "operator_attested_known_v1_service")

    def test_import_redacts_all_text_and_removes_personal_memory(self) -> None:
        raw = source_row("sensitive")
        secrets = "user@example.com 13812345678 password=hunter2 sk-abcdefghijk123456 111.228.35.186"
        raw["user_message"] = secrets
        raw["recent_context"] = ["user: " + secrets]
        raw["feedback_text"] = secrets
        raw["candidates"][0]["response"]["reply"] = secrets
        raw["candidates"][0]["response"]["memory_candidates"] = [{"type": "identity", "summary": secrets,
            "confidence": 0.9, "sensitivity": "private", "source_scope": "conversation"}]
        imported, pending = self.imported([raw])
        text = (imported / "pending.jsonl").read_text(encoding="utf-8")
        for secret in ("user@example.com", "13812345678", "hunter2", "sk-abcdefghijk123456", "111.228.35.186", "local-user", "conversation-one"):
            self.assertNotIn(secret, text)
        self.assertEqual(pending[0]["candidates"][0]["response"]["memory_candidates"], [])
        self.assertGreater(pending[0]["redactions"]["credential"], 0)

    def test_malformed_response_is_quarantined(self) -> None:
        malformed = source_row("bad")
        del malformed["candidates"][0]["response"]["voice_style"]
        imported, pending = self.imported([malformed])
        self.assertEqual(pending, [])
        self.assertEqual(sum(read_json(imported / "import_report.json")["exclusions"].values()), 1)

    def test_duplicate_source_events_and_conflicting_ids(self) -> None:
        original = source_row("duplicate")
        bad = source_row("conflict")
        changed = copy.deepcopy(bad)
        changed["user_message"] += " modified"
        imported, pending = self.imported([original, original, bad, changed])
        self.assertEqual([row["feedback_id"] for row in pending], ["duplicate"])
        report = read_json(imported / "import_report.json")
        self.assertEqual(report["exclusions"]["duplicate_source_events"], 1)
        self.assertEqual(report["exclusions"]["conflicting_feedback_ids"], 1)

    def test_withdrawal_is_terminal_even_before_capture_event(self) -> None:
        withdrawal = {"schema_version": "meguri-behavior-feedback-v2", "feedback_id": "withdrawn",
                      "status": "revoked", "consent_to_training": False}
        imported, pending = self.imported([withdrawal, source_row("withdrawn")])
        self.assertEqual(pending, [])
        self.assertEqual(read_json(imported / "import_report.json")["revoked_feedback_ids"], ["withdrawn"])

    def test_pending_records_are_never_auto_exported(self) -> None:
        imported, _ = self.imported([source_row("unapproved")])
        output = self.build(imported)
        self.assertEqual(self.feedback_rows(output), [])
        self.assertEqual(read_json(output / "quality_report.json")["exclusions"]["unapproved_or_review_revoked"], 1)

    def test_review_requires_digest_privacy_and_real_target(self) -> None:
        note = source_row("note")
        note["selected_candidate_id"] = None
        note["feedback_text"] = "请说得简短一些"
        imported, pending = self.imported([note])
        for digest, privacy, pattern in (("0" * 64, True, "digest"),
                (pending[0]["candidate_digest"], False, "privacy"),
                (pending[0]["candidate_digest"], True, "free-text")):
            with self.assertRaisesRegex(PipelineError, pattern):
                record_review(imported, self.ledger, feedback_id="note", candidate_digest=digest,
                    decision="approve", reviewer="human", privacy_reviewed=privacy, runtime=RUNTIME)
        self.assertFalse(self.ledger.exists())

    def test_reviewed_structured_correction_exports_without_training_the_note(self) -> None:
        raw = source_row("correction")
        raw["selected_candidate_id"] = None
        raw["feedback_text"] = "一条修正说明，不能当作回复"
        imported, rows = self.imported([raw])
        corrected = response("知道了，我会说得更简短。")
        self.approve(imported, rows, overrides={"corrected_response": corrected})
        output = self.build(imported)
        target = self.feedback_rows(output)[0]["messages"][-1]["content"]
        self.assertEqual(json.loads(target), corrected)
        self.assertNotIn(raw["feedback_text"], target)
        manifest = read_json(output / "dataset_manifest.json")
        self.assertEqual(sum(manifest["preference_counts"].values()), 2)

    def test_captured_structured_correction_also_requires_review(self) -> None:
        raw = source_row("source-correction")
        raw["selected_candidate_id"] = None
        raw["corrected_response"] = response("用户明确编写的修正回复")
        imported, rows = self.imported([raw])
        self.approve(imported, rows)
        output = self.build(imported)
        self.assertEqual(json.loads(self.feedback_rows(output)[0]["messages"][-1]["content"]), raw["corrected_response"])

    def test_review_runtime_metadata_must_be_supported(self) -> None:
        imported, rows = self.imported([source_row("runtime")])
        with self.assertRaisesRegex(PipelineError, "outfit/mode"):
            record_review(imported, self.ledger, feedback_id="runtime", candidate_digest=rows[0]["candidate_digest"],
                decision="approve", reviewer="human", privacy_reviewed=True, runtime={**RUNTIME, "interaction_mode": "sleep"})

    def test_grouped_export_and_dedup_have_separate_preference_files(self) -> None:
        one, two, duplicate = source_row("one"), source_row("two"), source_row("duplicate", session="other-conversation")
        duplicate["user_message"] = one["user_message"]
        duplicate["candidates"][0]["response"] = one["candidates"][0]["response"]
        imported, rows = self.imported([one, two, duplicate])
        self.approve(imported, rows)
        output = self.build(imported)
        samples = self.feedback_rows(output)
        self.assertEqual(len(samples), 2)
        self.assertEqual(len({row["metadata"]["source_split"] for row in samples}), 1)
        self.assertEqual(len({row["metadata"]["feedback_group_id"] for row in samples}), 1)
        manifest = read_json(output / "dataset_manifest.json")
        self.assertEqual(sum(manifest["preference_counts"].values()), 2)
        self.assertEqual(read_json(output / "quality_report.json")["exclusions"]["duplicate_sft_target"], 1)
        encoded = tokenize_assistant_only(samples[0], FakeTokenizer(), max_seq_length=20000)
        self.assertTrue(any(label != -100 for label in encoded["labels"]))

    def test_locked_scene_excludes_the_entire_connected_conversation(self) -> None:
        imported, rows = self.imported([source_row("locked", scene="locked-scene"), source_row("same-conversation")])
        self.approve(imported, rows)
        # A locked cases file is deliberately invalid; only IDs may be read.
        (self.splits / "locked_cases.jsonl").write_text("NEVER READ THIS FILE", encoding="utf-8")
        output = self.build(imported)
        self.assertEqual(self.feedback_rows(output), [])
        self.assertEqual(read_json(output / "quality_report.json")["exclusions"]["locked_scene_group"], 2)

    def test_baseline_scene_pins_split_and_conflicting_groups_are_excluded(self) -> None:
        imported, rows = self.imported([source_row("train-linked", session="one", scene="train-scene"),
            source_row("val-linked", session="two", scene="validation-scene"),
            source_row("conflict-train", session="three", scene="train-scene"),
            source_row("conflict-val", session="three", scene="validation-scene")])
        self.approve(imported, rows)
        output = self.build(imported)
        # Scene links also connect one/two through the conflicted conversation.
        self.assertEqual(self.feedback_rows(output), [])
        self.assertEqual(read_json(output / "quality_report.json")["exclusions"]["conflicting_existing_split_group"], 4)

    def test_export_is_reproducible_and_baseline_rows_remain_unchanged(self) -> None:
        imported, rows = self.imported([source_row(f"feedback-{index}", session=f"session-{index}") for index in range(12)])
        self.approve(imported, rows)
        one = self.build(imported)
        two = build_feedback_dataset(import_dir=imported, ledger=self.ledger, baseline_dir=self.baseline,
            split_root=self.splits, output_root=self.root / "second-outputs")
        first, second = read_json(one / "dataset_manifest.json"), read_json(two / "dataset_manifest.json")
        self.assertEqual(first["dataset_id"], second["dataset_id"])
        self.assertEqual(first["files"], second["files"])
        for split in ("train", "validation"):
            baseline = (self.baseline / f"{split}.jsonl").read_bytes()
            self.assertTrue((one / f"{split}.jsonl").read_bytes().startswith(baseline))
        self.assertEqual(validate_dataset_for_training(one)[0]["dataset_kind"], DATASET_KIND)

    def test_source_append_or_withdrawal_invalidates_export_before_training(self) -> None:
        imported, rows = self.imported([source_row("later-withdrawn")])
        self.approve(imported, rows)
        output = self.build(imported)
        with self.source.open("a", encoding="utf-8") as handle:
            handle.write(canonical_json({"schema_version": "meguri-behavior-feedback-v2", "feedback_id": "later-withdrawn",
                "status": "revoked", "consent_to_training": False}) + "\n")
        with self.assertRaisesRegex(PipelineError, "reimport and rebuild"):
            validate_dataset_for_training(output)
        reimported = import_feedback([self.source], self.root / "imports")
        rebuilt = self.build(reimported)
        self.assertEqual(self.feedback_rows(rebuilt), [])

    def test_unchanged_record_keeps_its_review_digest_after_source_append(self) -> None:
        imported, rows = self.imported([source_row("existing")])
        self.approve(imported, rows)
        with self.source.open("a", encoding="utf-8") as handle:
            handle.write(canonical_json(source_row("new")) + "\n")
        updated = import_feedback([self.source], self.root / "imports")
        current = [row for _, row in read_jsonl(updated / "pending.jsonl")]
        self.assertEqual(rows[0]["candidate_digest"], next(row["candidate_digest"] for row in current if row["feedback_id"] == "existing"))
        output = self.build(updated)
        self.assertEqual(len(self.feedback_rows(output)), 1)

    def test_review_revoke_invalidates_existing_export_and_excludes_rebuild(self) -> None:
        imported, rows = self.imported([source_row("review-revoked")])
        self.approve(imported, rows)
        output = self.build(imported)
        record_review(imported, self.ledger, feedback_id="review-revoked", candidate_digest=rows[0]["candidate_digest"],
            decision="revoke", reviewer="human")
        with self.assertRaisesRegex(PipelineError, "review ledger changed"):
            validate_dataset_for_training(output)
        self.assertEqual(self.feedback_rows(self.build(imported)), [])

    def test_tampered_artifacts_and_baseline_parent_are_rejected(self) -> None:
        imported, rows = self.imported([source_row("tamper")])
        self.approve(imported, rows)
        output = self.build(imported)
        with (output / "train.jsonl").open("a", encoding="utf-8") as handle:
            handle.write("{}\n")
        with self.assertRaisesRegex(PipelineError, "hash mismatch"):
            validate_dataset_for_training(output)
        forged = self.root / "forged-baseline"
        shutil.copytree(self.baseline, forged)
        manifest = read_json(forged / "dataset_manifest.json")
        manifest["source_build_id"] = "unknown-source"
        (forged / "dataset_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(PipelineError, "canonical B baseline"):
            build_feedback_dataset(import_dir=imported, ledger=self.ledger, baseline_dir=forged,
                split_root=self.splits, output_root=self.root / "forged-output")

    def test_rehashed_unapproved_target_cannot_bypass_review_provenance(self) -> None:
        imported, rows = self.imported([source_row("rewrite-target")])
        self.approve(imported, rows)
        output = self.build(imported)
        manifest = read_json(output / "dataset_manifest.json")
        split = "train" if manifest["feedback_counts"]["train"] else "validation"
        path = output / f"{split}.jsonl"
        samples = [row for _, row in read_jsonl(path)]
        samples[-1]["messages"][-1]["content"] = canonical_json(response("a malicious substituted target"))
        path.write_text("".join(canonical_json(row) + "\n" for row in samples), encoding="utf-8", newline="\n")
        manifest["files"][path.name] = sha256_file(path)
        (output / "dataset_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(PipelineError, "differs from approved"):
            validate_dataset_for_training(output)

    def test_previous_assignments_are_preserved_when_groups_merge(self) -> None:
        raw = [source_row(f"prior-{index}", session=f"session-{index}") for index in range(4)]
        imported, rows = self.imported(raw)
        self.approve(imported, rows)
        output = self.build(imported)
        second = build_feedback_dataset(import_dir=imported, ledger=self.ledger, baseline_dir=self.baseline,
            split_root=self.splits, output_root=self.root / "carried-output", split_seed="different-seed",
            previous_dataset=output)
        self.assertEqual(read_json(output / "dataset_manifest.json")["group_token_splits"],
                         read_json(second / "dataset_manifest.json")["group_token_splits"])

    def test_rehashed_group_evidence_cannot_hide_source_conversation(self) -> None:
        imported, rows = self.imported([source_row("rewrite-group")])
        self.approve(imported, rows)
        output = self.build(imported)
        manifest = read_json(output / "dataset_manifest.json")
        split = "train" if manifest["feedback_counts"]["train"] else "validation"
        path = output / f"{split}.jsonl"
        samples = [row for _, row in read_jsonl(path)]
        samples[-1]["metadata"]["feedback_group_tokens"] = ["conversation:forged"]
        path.write_text("".join(canonical_json(row) + "\n" for row in samples), encoding="utf-8", newline="\n")
        manifest["files"][path.name] = sha256_file(path)
        (output / "dataset_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(PipelineError, "group evidence differs"):
            validate_dataset_for_training(output)

    def test_unknown_dataset_kind_and_bad_baseline_counts_remain_fail_closed(self) -> None:
        forged = self.root / "forged"
        shutil.copytree(self.baseline, forged)
        manifest = read_json(forged / "dataset_manifest.json")
        manifest["train_count"] = 10
        (forged / "dataset_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(PipelineError, "2626/566"):
            validate_dataset_for_training(forged)
        manifest["dataset_kind"] = "unknown-augmentation"
        (forged / "dataset_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(PipelineError, "unsupported dataset"):
            validate_dataset_for_training(forged)

    def test_outputs_cannot_be_written_into_source_or_canonical_roots(self) -> None:
        write_jsonl(self.source, [source_row("path")])
        with self.assertRaisesRegex(PipelineError, "outside read-only"):
            import_feedback([self.source], self.source.parent / "derived")

    def test_cli_requires_explicit_review_and_supports_separate_workflow_steps(self) -> None:
        command = parser().parse_args(["review", "--import-dir", "pending", "--ledger", "review.jsonl",
            "--feedback-id", "one", "--candidate-digest", "a" * 64, "--decision", "approve", "--reviewer", "human"])
        self.assertFalse(command.privacy_reviewed)
        self.assertEqual(command.command, "review")


if __name__ == "__main__":
    unittest.main()
