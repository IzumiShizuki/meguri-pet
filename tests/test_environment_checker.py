from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "ops" / "scripts" / "check_environment_isolation.py"
FIXTURES = ROOT / "tests" / "fixtures" / "environment_isolation"


class EnvironmentIsolationCheckerTests(unittest.TestCase):
    def run_checker(self, fixture: Path | None = None) -> subprocess.CompletedProcess[str]:
        command = [sys.executable, str(CHECKER)]
        if fixture is not None:
            command.extend(["--fixture", str(fixture)])
        return subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=False)

    def test_repository_configuration_passes(self) -> None:
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PASS environment isolation", result.stdout)

    def test_each_fault_fixture_fails_with_expected_diagnostic(self) -> None:
        fixtures = sorted(FIXTURES.glob("*.json"))
        self.assertGreaterEqual(len(fixtures), 6)
        for fixture in fixtures:
            with self.subTest(fixture=fixture.name):
                expected = json.loads(fixture.read_text(encoding="utf-8"))["expected_error"]
                result = self.run_checker(fixture)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn(expected, result.stderr)


if __name__ == "__main__":
    unittest.main()
