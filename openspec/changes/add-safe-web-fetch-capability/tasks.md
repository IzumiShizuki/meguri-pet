## 1. Implementation

- [x] 1.1 Implement public destination validation, pinned per-hop transport and bounded fetch lifecycle.
- [x] 1.2 Implement data-only text extraction with bounded link preservation.
- [x] 1.3 Register opt-in web_fetch with existing capability scope, network and trust controls.

## 2. Verification and handoff

- [x] 2.1 Add offline adversarial destination, redirect, response, cancellation and extraction regressions.
- [x] 2.2 Verify Skill dependency and frozen capability/ReAct integration; run required Java checks.
- [x] 2.3 Record actual public-route availability without bypass, document limitations, validate OpenSpec and deliver candidate commit/bundle for review.

## 3. Review correction

- [x] 3.1 Reproduce source/link budget starvation through the real capability and ReAct path, including reordered fields.
- [x] 3.2 Reserve independent source/link/body budgets and retain complete URLs or explicitly omit them.
- [x] 3.3 Run offline full regression and deliver revised commits/report with the complete d136576 prerequisite; do not claim primary-tree verification.
