from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from training.llm.scripts.switch_staging_model import switch


class StagingRollbackTests(unittest.TestCase):
    def test_switch_and_rollback_do_not_rebuild(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            registry = root / "registry.json"
            routing = root / "routing.json"
            registry.write_text(
                json.dumps(
                    {
                        "models": [
                            {"model_id": "candidate", "status": "staging_candidate"},
                            {"model_id": "last", "status": "staging_active"},
                        ]
                    }
                ),
                encoding="utf-8",
            )
            routing.write_text(
                json.dumps(
                    {
                        "candidate_enabled": False,
                        "candidate_model_id": "candidate",
                        "last_good_model_id": "last",
                    }
                ),
                encoding="utf-8",
            )
            enabled = switch(registry, routing, "candidate")
            rolled_back = switch(registry, routing, "last-good")
            self.assertTrue(enabled["candidate_enabled"])
            self.assertFalse(rolled_back["candidate_enabled"])
            self.assertFalse(rolled_back["rebuild_required"])


if __name__ == "__main__":
    unittest.main()
