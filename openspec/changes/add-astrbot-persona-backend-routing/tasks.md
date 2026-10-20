## 1. Persona routing

- [x] 1.1 Implement opt-in exact-ID routing through AstrBot's effective-persona resolver with native fallback on lookup failure.
- [x] 1.2 Integrate the policy before natural-language Meguri actions while preserving explicit commands, plugin ownership and permission boundaries.
- [x] 1.3 Add plugin configuration and document setup, compatibility, history boundaries and rollback.

## 2. Validation and handoff

- [x] 2.1 Cover persona selection, defaults/overrides, switching/isolation, unavailable APIs and entrypoint side effects with focused tests.
- [x] 2.2 Run applicable AstrBot regression tests and inspect a newly built plugin upload archive.
- [x] 2.3 Verify requirements against implementation, run strict OpenSpec validation and commit only this change locally.

Production follow-up: install the reviewed ZIP after deployment authorization, configure the actual Meguri persona IDs and enable the switch, then verify with real native/Meguri conversations. No live configuration, restart, QQ message or push is part of this local implementation.
