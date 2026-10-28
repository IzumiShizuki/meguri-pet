## 1. Retrieval

- [x] 1.1 Add red/green routing, wrapped-query and transport encoding regressions.
- [x] 1.2 Implement relevance filtering and a single deadline-bounded recovery query with diagnostics.
- [x] 1.3 Separate technical weather intent and prevent implicit global location use; verify positive forecast controls.

## 2. Turn and context integrity

- [x] 2.1 Reproduce terminal polling race and fix live/reconnect completion.
- [x] 2.2 Inspect prompt chronology and failure recovery; bind current input and correction precedence with regression tests.
- [x] 2.3 Respect conversation preferences and prevent unsolicited training alternatives when training mode is false.
- [x] 2.4 Inspect empty skill catalogue and desktop recovery evidence without installing or enabling skills.

## 3. Verification and delivery

- [x] 3.1 Run relevant Java, Python, TypeScript and desktop checks; record failures and untested boundaries.
- [x] 3.2 Run bounded isolated Core dialogue and public-search probes where available without formal memory writes or service disruption.
- [ ] 3.3 Review final diff and secret boundaries, validate OpenSpec, commit and push the separate fix branch, verify remote SHA.
