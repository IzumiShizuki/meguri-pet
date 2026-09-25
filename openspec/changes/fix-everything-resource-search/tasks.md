## 1. Reproduction and search safety policy

- [x] 1.1 Add a deterministic regression test where Everything returns a safe file outside the former workspace roots and the selector currently reports no safe match.
- [x] 1.2 Trace raw Everything hits through canonicalization, local-volume classification, and safety filtering.
- [x] 1.3 Replace root-based rejection with a shared canonical local-file safety policy that retains protected-path exclusions.

## 2. Resource selection diagnostics

- [x] 2.1 Preserve metadata-only results, confirmation gating, result limits, and normal no-match behavior.
- [x] 2.2 Distinguish raw no-match from all-matches-unsafe without exposing protected path details.

## 3. Verification

- [x] 3.1 Add coverage for a second local drive, protected paths, UNC paths, inaccessible entries, and empty Everything results.
- [x] 3.2 Run targeted resource tests, Java module verification, and strict OpenSpec validation.
