## 1. Lifecycle diagnosis

- [x] 1.1 Map desktop/session, model gateway, retrieval bridge and persistence boundaries to existing evidence; record uncovered scenarios.
- [x] 1.2 Reproduce desktop reopening/reset races, add regressions and fix isolation and immediate-send behavior.

## 2. Models and retrieval

- [x] 2.1 Exercise independent planner/answer HTTP routes and planner failure recovery; repair reproduced defects.
- [x] 2.2 Add and test the distinct input-model check/rewrite route, preserving authoritative raw input and bounded fallback; document configuration.
- [x] 2.3 Exercise real HTTP RAG/memory bridge success, empty, invalid and unavailable outcomes across subsequent turns; repair reproduced defects.
- [x] 2.4 Verify bounded retrieval timeout/cancellation and that late results do not contaminate another turn or session.

## 3. Integration and verification

- [x] 3.1 Validate multi-turn context and restart recovery using existing durable runtime tests and new missing boundary assertions.
- [x] 3.2 Run relevant broad Python, Java, desktop and SDK suites and build/type checks.
- [x] 3.3 Write coverage/result matrix with exact commands, source/deployment limits and remaining operational actions; verify OpenSpec completeness/correctness/coherence.
