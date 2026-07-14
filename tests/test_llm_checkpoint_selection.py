from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from training.llm.scripts.common import PipelineError
from training.llm.scripts.select_checkpoint import select


def report(score: float, adapter: str, digest: str, *, locked: bool = False) -> dict:
    return {
        "status": "pass",
        "selection_eligible": True,
        "locked_eval_accessed": locked,
        "dataset_id": "dataset-one",
        "adapter_sha256": digest,
        "model": {"adapter_path": adapter},
        "composite": {"score": score},
        "provenance": {"training_config_sha256": "config-one"},
    }


class CheckpointSelectionTests(unittest.TestCase):
    def test_selects_highest_validation_composite(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            paths = []
            for index, value in enumerate((0.7, 0.8), 1):
                path = root / f"report-{index}.json"
                path.write_text(json.dumps(report(value, f"adapter-{index}", f"hash-{index}")), encoding="utf-8")
                paths.append(path)
            result = select(paths, root / "selection.json")
            self.assertEqual(result["selected"]["adapter_path"], "adapter-2")
            self.assertFalse(result["locked_eval_used"])

    def test_rejects_locked_eval_influence(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            paths = []
            for index, locked in enumerate((False, True), 1):
                path = root / f"report-{index}.json"
                path.write_text(
                    json.dumps(report(0.7, f"adapter-{index}", f"hash-{index}", locked=locked)),
                    encoding="utf-8",
                )
                paths.append(path)
            with self.assertRaises(PipelineError):
                select(paths, root / "selection.json")


if __name__ == "__main__":
    unittest.main()
