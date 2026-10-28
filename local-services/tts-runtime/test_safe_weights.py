"""Run with the installed GPTSoVits Python; does not require additional packages."""
import importlib
import tempfile
import unittest
from pathlib import Path

import torch

module = importlib.import_module("local-services.tts-runtime.model_manager")


class UnsafeCheckpoint:
    def __init__(self, marker):
        self.marker = marker

    def __reduce__(self):
        return eval, (f"open({str(self.marker)!r}, 'w').write('unsafe pickle executed')",)


class SafeWeightsTest(unittest.TestCase):
    def test_tensor_mapping_passes(self):
        with tempfile.TemporaryDirectory(prefix="meguri-safe-weights-") as directory:
            target = Path(directory) / "safe.ckpt"
            torch.save({"weight": {"layer": torch.ones(2, 2)}, "config": {"version": "v2Pro", "sample_rate": 32000}}, target)
            module.verify_safe_weights(target)

    def test_executable_pickle_is_rejected_without_execution(self):
        with tempfile.TemporaryDirectory(prefix="meguri-safe-weights-") as directory:
            marker = Path(directory) / "must-not-exist.txt"
            target = Path(directory) / "unsafe.ckpt"
            torch.save({"weight": UnsafeCheckpoint(marker)}, target)
            with self.assertRaisesRegex(ValueError, "安全加载"):
                module.verify_safe_weights(target)
            self.assertFalse(marker.exists())


if __name__ == "__main__":
    unittest.main()
