## 1. Diagnose and reproduce
- [x] 1.1 Trace import, binding, dependency, frozen-turn and result-to-chat boundaries.
- [x] 1.2 Add focused dependency-change and failure-reason regressions before implementation.

## 2. Minimal correction
- [x] 2.1 Reuse live dependency validation and correct disabled projection without changing stored reports.
- [x] 2.2 Report frozen-turn dependency exposure and discovery semantics accurately.
- [x] 2.3 Preserve safe failure reasons, retries and structured untrusted results through chat.

## 3. Verify and deliver
- [x] 3.1 Run targeted regressions and complete offline Java suite; document exact scope and gaps.
- [x] 3.2 Deliver independent commit, bundle prerequisites and readable acceptance guide for primary review.
