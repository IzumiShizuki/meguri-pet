## 1. Regression Coverage

- [x] 1.1 Add a focused splitter regression test for an oversized single bilingual pair, exact content reconstruction, and distinct continuation pages
- [x] 1.2 Add an integrated AstrBot rendering-path test that verifies each continuation slice produces its corresponding outbound image in order

## 2. Pagination Fix

- [x] 2.1 Implement deterministic bounded partitioning for both sides of an oversized bilingual pair
- [x] 2.2 Update bilingual splitting to retain one panel for short pairs and emit ordered formatted panels for oversized pairs
- [x] 2.3 Ensure the multi-page delivery path renders and sends each page from its own slice with page-local text fallback

## 3. Verification

- [x] 3.1 Run the focused AstrBot pagination regressions and the complete AstrBot plugin test suite
- [x] 3.2 Validate the OpenSpec change and confirm no temporary debug instrumentation remains
