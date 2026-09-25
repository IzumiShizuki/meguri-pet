from __future__ import annotations

import copy
import unittest

from ops.scripts.check_exposure_ledger import load_ledger, validate_ledger


class ExposureLedgerTests(unittest.TestCase):
    def test_ledger_is_complete_and_matches_compose(self) -> None:
        self.assertEqual(validate_ledger(load_ledger()), [])

    def test_production_gate_fails_on_unresolved_existing_exposure(self) -> None:
        errors = validate_ledger(load_ledger(), production_gate=True)
        self.assertTrue(errors)
        self.assertTrue(all(error.startswith("production_gate ") for error in errors))

    def test_unregistered_compose_port_fails_closed(self) -> None:
        ledger = copy.deepcopy(load_ledger())
        ledger["entries"] = [entry for entry in ledger["entries"] if entry["id"] != "meguri-staging-loopback"]
        errors = validate_ledger(ledger)
        self.assertTrue(any("compose_exposure staging.core" in error for error in errors))

    def test_unapproved_public_meguri_entry_fails_closed(self) -> None:
        ledger = copy.deepcopy(load_ledger())
        staging = next(entry for entry in ledger["entries"] if entry["id"] == "meguri-staging-loopback")
        staging["declared_binding"] = "all-interfaces"
        errors = validate_ledger(ledger)
        self.assertTrue(any("not approved-temporary" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
