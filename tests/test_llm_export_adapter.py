from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from training.llm.scripts.common import sha256_file
from training.llm.scripts.export_adapter import adapter_hash, export


class AdapterExportTests(unittest.TestCase):
    def test_export_binds_experiment_and_selection_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            experiment = root / "experiment"
            source = experiment / "final_adapter"
            source.mkdir(parents=True)
            (source / "adapter_config.json").write_text("{}\n", encoding="utf-8")
            (source / "adapter_model.safetensors").write_bytes(b"adapter")
            digest, _ = adapter_hash(source)
            manifest = {
                "status": "pass",
                "experiment_id": "experiment-one",
                "base_model_repo": "repo",
                "base_model_revision": "revision",
                "tokenizer_revision": "revision",
                "final_adapter": str(source),
            }
            experiment_path = experiment / "experiment_manifest.json"
            experiment_path.write_text(json.dumps(manifest), encoding="utf-8")
            selection_path = root / "selection.json"
            selection_path.write_text(
                json.dumps(
                    {
                        "selected": {
                            "adapter_path": str(source),
                            "adapter_sha256": digest,
                        }
                    }
                ),
                encoding="utf-8",
            )

            exported = export(experiment, root / "exports", selection_path)
            result = json.loads((exported / "export_manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(result["adapter_sha256"], digest)
            self.assertEqual(result["experiment_manifest_sha256"], sha256_file(experiment_path))
            self.assertEqual(result["validation_selection_sha256"], sha256_file(selection_path))


if __name__ == "__main__":
    unittest.main()
