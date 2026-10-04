## 1. Inventory and contract

- [x] 1.1 Inventory repository rules, Todo/voice/delivery paths and related OpenSpec artifacts without changing history.
- [x] 1.2 Define acceptance scenarios and safe capability/offline boundaries; perform separate read-only production probes.

## 2. Intent and review

- [x] 2.1 Implement shared Shanghai time conversion and bounded natural-language intent parsing with clarification and unsupported-action handling.
- [x] 2.2 Route typed/voice draft submission through a shared review controller and accessible confirmation/clarification UI.

## 3. Server writes and lifecycle

- [x] 3.1 Add durable account-bound creation receipts, safe reconciliation/retry and exact server-response validation.
- [x] 3.2 Extend trusted IPC contracts for restoring pending writes and confirmed edit/cancellation controls.

## 4. Delivery correctness

- [x] 4.1 Fix timezone interpretation, stale snapshot behavior and fail-closed occurrence persistence; apply acknowledged mutations immediately.
- [x] 4.2 Verify local trigger, missed-window, restart, disconnect and failure paths without external test sends.

## 5. Verification and delivery

- [x] 5.1 Cover parsing, clarification, confirmation, server payload/response, rejection, ambiguous writes, retry, account binding and concurrency with tests.
- [x] 5.2 Run desktop tests, preload checks, typecheck, build and strict OpenSpec validation.
- [x] 5.3 Update user documentation and write an evidence-based verification report, preserving unrelated incomplete tasks.
