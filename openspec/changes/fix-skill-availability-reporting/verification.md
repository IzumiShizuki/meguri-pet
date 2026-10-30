# Verification, 2026-10-30

Independent branch: `codex/skill-usability-20261030`, based on `a2db59735a0a38de20a891a8e46c47d49191e0d8`. Main checkout and live Core were not modified.

## Final result

Offline Java suite: **939 tests, 934 passed, 5 existing skips, zero failures/errors**, Maven BUILD SUCCESS in 51.013 seconds. The preserved a2db597 Surefire archive has 922 test identities; comparison confirms all old outcomes unchanged and all 17 new tests passed. Evidence is in the sibling workspace delivery directory `skill-usability-candidate-20261030` (`verification-summary.json`, `skill-usability-surefire.zip`, `skill-usability-full-accepted.log`).

Run from the workspace root using its existing isolated runner:

```powershell
.\run-isolated-retrieval-tests.ps1 -RepoName meguri-reply-constraints -SocketDirectory 'C:\Users\IzumiShizuki\Documents\Codex\2026-10-28\task\meguri-retrieval-baseline\tmp\jdk-unix-sockets' -LogName skill-usability-full-accepted.log
```

The runner uses existing Java 21 and offline Maven dependencies, synthetic home/config and the approved Java execution environment. It does not install dependencies, contact live Core or change network/security settings.

## Reproduction and intermediate failures

- Focused red tests established stale dependency projections, incorrect active/discovery semantics and loss of safe failure codes. One initial web-fetch test had an incorrect DNS fixture; the corrected fixture separately reproduced the actual error-code loss.
- Further red tests established blocked candidate starvation, MCP failure classification, the approved MCP read ReAct gate, and the absent outputSchema contract gap.
- Two intermediate full runs each had 939 tests, zero failures, one error and five skips. The new write-policy negative fixture initially violated descriptor construction rules (WRITE side effect, then retry/idempotency); fixing only that fixture allowed the intended validation assertion to run. The class then passed 2/2 before the final full run. Product constraints and assertions were not relaxed.
- A full sandbox run had 7 failures and 187 errors: the first actionable exception was Java HttpClient's internal Pipe failing with `Permission denied: connect`; file-rename failures in sandbox JUnit Temp also occurred. With approved execution permission, representative HTTP-adapter and Skill-availability classes passed 15/15, then the same full suite passed. No security/network policy was changed. An unquoted PowerShell comma in the first representative command matched zero tests; its corrected command supplied the quoted class list.

## Scope and remaining acceptance

Tests exercise real Turn/ReAct/frozen-capability/approval/observation/provider-context code with synthetic MCP data and a synthetic final model provider. HTTP adapter behavior is tested separately with controlled DNS/HTTP fixtures. They prove structured data and safe failure reasons reach the final provider as USER_DATA/UNTRUSTED, not real third-party or real-model success.

No third-party Skill was installed or activated, no live model requests or public probes were sent, and the earlier denied public-address probe was not retried. Automatic approval request/wait/resume UI is not implemented. Approved MCP reads without an idempotency key retain at most two attempts; this is not single-use approval. Existing tracked external-effect/UNKNOWN_OUTCOME behavior remains authoritative.

Primary review and integration verification remain separate. Incremental bundle requires a2db597; the combined web-fetch/Skills bundle requires full prerequisite d1365765754ed6ae3f0cb6f84f8addfd7b948387 and does not contain its history. Bundle/reverse-patch checks apply only to the independent copy.

## Main integration review
Cherry-picked as 93e90eb. Added malformed JSON/SSE real-executor regressions: red 2 failures / 4 passes, then 6 passes with protocol-invalid non-retryable classification and IO recovery control. Main combined full Java: 1011 passed, 5 skipped; prior 988 statuses unchanged, 28 added pass (includes recurring tests). No real activation/deployment.
