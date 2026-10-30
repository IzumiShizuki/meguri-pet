# Verification

Implemented the desktop scope on baseline `1d5b00dc` without touching the running Core or real Todo data. See `docs/timeprism-improvement-and-regression-2026-10-30.md` for evidence, follow-up design and remaining acceptance.

- New isolated regression: 10 failed before implementation, then 10 passed.
- Targeted desktop suites: 57 passed, 0 failed.
- Full desktop unit suite: 534 passed, 0 failed, 0 skipped.
- Typecheck: passed after correcting the new test fixture's fetch parameter type.
- Build: passed; existing bundle size warning remains.
- OpenSpec strict / diff check: passed.
- Fifteen pre-existing user files: hashes unchanged.
- First sandbox run: EPERM while renaming Vite cache, 0 tests; excluded from regression counts.
- No Java changes in this stage; no production UI/Core/model/website acceptance claimed.
- The site repository was read only at `ec29b37c`. Pure server query, AstrBot recurring drafts and cross-device delivery remain documented follow-ups.
- web_fetch review finding closed at `a2db597`; candidate archive 917 pass/5 skip, not imported or activated here.

Evidence: `reports/timeprism-parity-20261030/red-executed.json`, `targeted.json`, `desktop-full.json`, `verification-summary.json`, `preserved-user-files.json`.
