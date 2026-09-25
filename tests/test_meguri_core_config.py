from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from services.meguri_core.config import candidate_data_roots, resolve_data_root


class MeguriCoreConfigTests(unittest.TestCase):
    def test_explicit_environment_value_wins(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw) / "meguri-pet-llm-training"
            root.mkdir()
            override = root / "custom" / "datasets" / "meguri"
            resolved = resolve_data_root(root=root, env_value=str(override))
            self.assertEqual(resolved, override)

    def test_prefers_local_dataset_root_when_present(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw) / "meguri-pet"
            local = root / "datasets" / "meguri"
            local.mkdir(parents=True)
            resolved = resolve_data_root(root=root)
            self.assertEqual(resolved, local)

    def test_falls_back_to_sibling_primary_repo_dataset_root(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            base = Path(raw)
            worktree = base / "meguri-pet-llm-training"
            sibling = base / "meguri-pet" / "datasets" / "meguri"
            worktree.mkdir()
            sibling.mkdir(parents=True)
            resolved = resolve_data_root(root=worktree)
            self.assertEqual(resolved, sibling)

    def test_candidate_roots_keep_primary_repo_first(self) -> None:
        roots = candidate_data_roots(Path("D:/program/meguri-pet-llm-training"))
        self.assertEqual(roots[0], Path("D:/program/meguri-pet-llm-training/datasets/meguri"))
        self.assertEqual(roots[1], Path("D:/program/meguri-pet/datasets/meguri"))


if __name__ == "__main__":
    unittest.main()
