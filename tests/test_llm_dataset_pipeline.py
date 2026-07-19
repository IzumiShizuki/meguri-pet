from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from training.llm.scripts.common import PipelineError, canonical_json, read_json, read_jsonl
from training.llm.scripts.dataset import SOURCE_BUILD_ID, build_dataset, validate_dataset


def source_row(sample_id: str, language: str, split: str, scene_id: str, outfit: str) -> dict:
    suffix = "jp" if language == "jp" else "zh"
    return {
        "sample_id": f"{sample_id}_{suffix}",
        "build_id": SOURCE_BUILD_ID,
        "character_id": "meguri",
        "language": language,
        "target": {"speaker": "Meguri", "reply": "おかえり" if language == "jp" else "欢迎回来"},
        "messages": [
            {"role": "system", "content": "legacy"},
            {"role": "user", "content": "context"},
            {"role": "assistant", "content": "legacy target"},
        ],
        "metadata": {
            "line_id": sample_id,
            "scene_id": scene_id,
            "source_script": f"{scene_id}.ks",
            "source_order": 1,
            "split": split,
            "relationship_stage": "sibling",
            "expression_tag": "neutral",
            "expression_intensity": "low",
            "voice_style": "neutral",
            "outfit_code": outfit,
            "source_file": "data/meguri/aligned_v1/manifests/dialogue_master.csv",
            "source_row_number": 1,
            "source_line_id": sample_id,
        },
    }


class LlmDatasetPipelineTests(unittest.TestCase):
    def make_source(self, root: Path) -> tuple[Path, Path, dict[str, dict[str, int]]]:
        data_root = root / "source"
        text_root = data_root / "exports" / "text_sft"
        text_root.mkdir(parents=True)
        rows = {
            "jp_train.jsonl": source_row("train-line", "jp", "train", "train-scene", "01"),
            "zh_train.jsonl": source_row("train-line", "zh", "train", "train-scene", "01"),
            "jp_validation.jsonl": source_row("val-line", "jp", "validation", "val-scene", "03"),
            "zh_validation.jsonl": source_row("val-line", "zh", "validation", "val-scene", "03"),
        }
        for name, row in rows.items():
            (text_root / name).write_text(canonical_json(row) + "\n", encoding="utf-8")
        build_report = {
            "build_id": SOURCE_BUILD_ID,
            "decision": "GO",
            "counts": {
                "text_sft": {
                    "jp": {"train": 1, "validation": 1, "test": 1},
                    "zh": {"train": 1, "validation": 1, "test": 1},
                }
            },
        }
        (data_root / "build_report.json").write_text(
            json.dumps(build_report, ensure_ascii=False), encoding="utf-8"
        )
        split_root = root / "splits"
        split_root.mkdir()
        (split_root / "test_scene_ids.txt").write_text("locked-scene\n", encoding="utf-8")
        expected = {
            "train": {"jp": 1, "zh": 1},
            "validation": {"jp": 1, "zh": 1},
            "locked_eval": {"jp": 1, "zh": 1},
        }
        return data_root, split_root, expected

    def test_builds_independent_manifest_and_json_targets(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            data_root, split_root, expected = self.make_source(root)
            output = build_dataset(
                data_root=data_root,
                split_root=split_root,
                output_root=root / "derived",
                expected_counts=expected,
            )
            manifest = read_json(output / "dataset_manifest.json")
            quality = read_json(output / "quality_report.json")
            train_rows = [row for _, row in read_jsonl(output / "train.jsonl")]
            self.assertEqual(manifest["train_count"], 2)
            self.assertEqual(manifest["validation_count"], 2)
            self.assertFalse(manifest["locked_eval_policy"]["content_read_by_converter"])
            self.assertEqual(quality["status"], "pass")
            assistant = json.loads(train_rows[0]["messages"][-1]["content"])
            self.assertEqual(set(assistant), {
                "reply", "expression_tag", "expression_intensity", "voice_style", "memory_candidates"
            })
            self.assertEqual(assistant["memory_candidates"], [])
            self.assertEqual(train_rows[0]["metadata"]["interaction_mode"], "work")
            self.assertEqual(
                train_rows[0]["metadata"]["provenance"]["source_export"],
                "exports/text_sft/jp_train.jsonl",
            )
            self.assertFalse(Path(train_rows[0]["metadata"]["provenance"]["source_export"]).is_absolute())

    def test_refuses_output_inside_source_root(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            data_root, split_root, expected = self.make_source(root)
            with self.assertRaisesRegex(PipelineError, "read-only source"):
                build_dataset(
                    data_root=data_root,
                    split_root=split_root,
                    output_root=data_root / "derived",
                    expected_counts=expected,
                )

    def test_manifest_validation_detects_tampering(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            data_root, split_root, expected = self.make_source(root)
            output = build_dataset(
                data_root=data_root,
                split_root=split_root,
                output_root=root / "derived",
                expected_counts=expected,
            )
            with (output / "train.jsonl").open("a", encoding="utf-8") as handle:
                handle.write("{}\n")
            result = validate_dataset(output, split_root=split_root, expected_counts=expected)
            self.assertEqual(result["status"], "fail")
            self.assertEqual(result["blockers"]["json_parse_errors"], 1)
            self.assertIn("train.jsonl", result["manifest_file_hash_errors"])


if __name__ == "__main__":
    unittest.main()
