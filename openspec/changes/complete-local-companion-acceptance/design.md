## Context
See proposal.md. The current Core predates dialogue repairs; dirty timestamp UI belongs to other user work. The site has deployed weekday recurring rules and dated instances. Existing skills and community bridge boundaries should be reused.
## Goals / Non-Goals
Goals: repair narrow wiring/contract defects and create reproducible acceptance evidence. Non-goals: new credentials, unknown installers, model downloads, unrestricted self-modification, production task mutations or unrelated UI changes.
## Decisions
- The specific 2026-10-30 approval permits local cherry-pick of the two reviewed retrieval commits and three reply-constraint implementation commits. Keep query fidelity intact, skip obsolete delivery-only commits, resolve documentation conflicts from verified evidence, and run combination tests in an isolated build tree. Integration does not authorize deployment, push, credentials or production Todo mutations.
- Persona speechStyle joins trusted persona policy; canonical LORE gains purpose labeling inside untrusted context. Do not use general RAG as a persona command source.
- Inspect site source as the API authority, preserve recurrence/instance fields through existing authenticated Todo boundaries and test with fixtures. Do not infer site rules from display text.
- Reuse capability policy, approval, quarantine and dispatch. Test actual controlled execution rather than accepting registry presence.
- Serialize Java test/build runs and all deployment actions under the coordinator. Use isolated identities and a scratch/read-only memory backend; never real memory data.
## Risks / Trade-offs
- Query fidelity follow-up changes only WebSearchPolicy and its regression tests: remove recognized output requests outside quotes, retain subject words/comparisons, and require English word boundaries. This is independent of the pending RFC/domain/LORE candidate integration; no candidate code is copied around its approval rejection.
- External credentials or Windows consent unavailable → report exact blocked action and continue independent checks.
- Current health lacks code SHA → record process start/classpath and class/artifact hashes at launch, without calling dataset ID a code version.
- Live site/UI writes risk user tasks → use isolated contract fixtures; report production and UI gaps separately.
## Migration Plan
No destructive schema migration is intended. Record current service and reconstruct a known source rollback artifact. Verify idle requests, acquire normal Windows confirmation if needed, replace only the verified Core, check readiness and runtime evidence, then execute bounded live probes. Roll back on failed startup. Never touch unrelated applications.
