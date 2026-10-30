# Web search query fidelity repair — 2026-10-30

This follow-up repairs verified query normalization defects without applying the separately reviewed but unapproved RFC/domain/LORE candidates. The production change is confined to `WebSearchPolicy`; no provider, credential, model, permission or running service was changed. Existing user desktop edits remain separate.

## Before and after

The first 15 new regression cases ran against unchanged `c9b762e` code in an isolated build tree: **4 passed, 11 failed, 0 errors**. After the repair all 15 passed. Five additional quote/Unicode/pipeline checks bring the final new suite to **20 passed**.

| Input/subject | Previous normalized query | Repaired behavior |
| --- | --- | --- |
| `请搜索 文本总结算法 的局限` | `文本` | Retains the whole subject |
| `请搜索 "文本总结方法" 的论文，并给出来源。` | `"文本` | Retains the quoted title and paper subject |
| `currentColor CSS specification` | `Color CSS specification current` | Retains the complete CSS identifier |
| `searchable encryption` | `able encryption` | Does not mistake a word prefix for the search verb |
| `Could you search for Kotlin coroutine cancellation, and provide two official sources?` | Includes `for` and the presentation request | Sends `Kotlin coroutine cancellation` |
| English explanatory sentence followed by a TaskGroup lookup | Includes the explanatory sentence and output request | Sends `Python TaskGroup exception propagation` |
| `搜索 Python 内置函数，并比较 sorted 与 sort` | Drops the comparison | Retains the meaningful comparison clause |

The normalizer now recognizes English word boundaries, removes bounded presentation suffixes only outside quotations, preserves quoted mixed-script text and spaces, and avoids splitting a supplementary Unicode character at the existing 256-unit limit. Search intent and explicit offline authority are unchanged. It remains a bounded deterministic normalizer, not a general natural-language parser.

## Validation

All Java work ran under `reports/query-fidelity-20261030/isolated`; production `target/classes` was not rebuilt. The snapshot contains 779 source/config/contract files plus the same 404 existing character resources used by the independently validated baseline. No user memory, billing database, production credentials or unapproved candidate code was copied into the test fixture.

The existing Java 21.0.11 test-process workaround was verified in the new directory with `SelectorProbe`: the long `jdk.net.unixdomain.tmpdir` path makes the JDK select its built-in TCP fallback. This does not repair the underlying AF_UNIX connect issue or change any OS/network permission.

| Run | Tests | Passed | Failures/errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| New cases before repair | 15 | 4 | 11 / 0 | 0 |
| First after: new cases + existing search/retrieval | 47 | 47 | 0 / 0 | 0 |
| Final query suite + representative HTTP/runtime checks | 85 | 85 | 0 / 0 | 0 |
| Full current source + query repair | 758 | 753 | 0 / 0 | 5 |
| Retained independently verified baseline | 738 | 733 | 0 / 0 | 5 |

All 738 shared test outcomes match the retained baseline; all 20 new cases pass. The five skips are unchanged opt-in live checks: Everything, two PostgreSQL recovery checks, Shizuki UI control, and public ModelScope package download/import. They are not accepted as live successes.

Reproducible commands, from the project root:

```powershell
& reports/query-fidelity-20261030/run-isolated-tests.ps1 -Tests 'WebSearchQueryFidelityTest,WebSearchGatewayTest,WebRetrievalProviderTest,AgentRuntimeSpringConfigurationTest,HttpMcpAdapterTest,InputModelCheckerTest,InputModelCheckerFactoryTest,TurnRuntimeTest' -LogName targeted-after.log
& reports/query-fidelity-20261030/run-isolated-tests.ps1 -LogName full-after.log
openspec validate complete-local-companion-acceptance --type change --no-interactive
git diff --check
```

Both Maven runs exited 0. OpenSpec validation and diff whitespace checks passed. The isolated script uses Maven offline mode, cached dependencies, a scratch user home, disabled production connection opt-ins and local HTTP fixtures.

Evidence: `before-summary.json`, `before-surefire/`, `targeted-after-summary.json`, `full-after-summary.json`, `full-after.log`, `source-manifest-before.json`, `resource-hashes.json`, all in the ignored `reports/query-fidelity-20261030/` directory.

## Public upstream check: still insufficient evidence

The unmodified Bing RSS gateway made exactly four sequential public requests: before/after query text for Kotlin coroutine cancellation and Python TaskGroup exception propagation. No API key, model request, result-page fetch or service restart was involved.

All four returned RSS status `ok`, five candidates each. **All 20 candidates failed the intended-subject relevance check.** Kotlin results remained generic language pages; the Python request changed from `need` dictionary/education results to generic Python pages after normalization. Neither response established the requested technical facts. Sequential observations are not a controlled causal provider experiment.

The controlled pipeline test proves that a relevant fixture survives normalized-query filtering and wrong-topic fixtures are rejected with at most one retry. The public observation confirms the unresolved upstream quality limit; it does not prove live search success. No fabricated result, weakened relevance threshold, replacement paid service or credential change was used.

Evidence and reproduction: `public-search-observations.json`, `public-search.log`, `run-public-search.ps1`. This is an isolated invocation of the actual gateway code, not a deployed Core/model/UI acceptance run.

## Candidate integration and runtime status

The new instruction to continue targeted modifications justified one review retry of the original local bundle fetch/cherry-pick operation. Automatic approval rejected it before process creation, stating that general modification authorization did not explicitly approve commits `c3849df` and `9ebf8ab`. Neither fetch nor cherry-pick executed. No alternate patch/application path was attempted. Their independently verified 769-pass/5-skip result remains valid, but their code is not in the main tree or runtime.

The exact pending decision remains: authorize cherry-picking `c3849df97725f18245d91d27f595b973868ad69c` and `9ebf8ab0d8c847411f6b686a3b18927bca07cb0e` onto local `fix/dialogue-reliability-20261028`, without push, merging the main branch or restarting Core. No public approval ID was returned. The RFC/domain/long canonical-query work remains blocked on that decision.

Read-only health inspection confirmed the existing Core PID67344, build_id `meguri_v2_02c3db0c507d7c2d`, configured provider and health `ok`; the prior recorded running-code revision is `b4b03a4`. Desktop12568, Harness5172, gateway58448 and speech23364 remained present. The dataset build ID is not a code SHA. This query repair is local source only and was not deployed.

Remaining independent limits: response-format candidate integration awaits its separate task; Harness model cooldown needs upstream diagnosis; MCP administration approval and real desktop two-end Todo acceptance remain pending. No additional real task, memory entry or external-agent request was created by this follow-up.
