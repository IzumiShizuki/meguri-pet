# Dialogue evaluation repair — 2026-10-28

## Saved baseline and scope

The pre-repair workspace was saved and pushed first:
[`47462684e7f121ab9b429bb0feab9d54e582438f`](https://github.com/IzumiShizuki/meguri-pet/commit/47462684e7f121ab9b429bb0feab9d54e582438f),
branch `codex/companion-memory-corrections`. Remote SHA was verified before editing.
Repairs are on `fix/dialogue-reliability-20261028`; no merge or deployment is part of this change.

The original 301-input evaluation was evidence for targeted repairs, not proof of UI, compression-limit,
long-term-memory, or installed-skill failures. Tests below use synthetic identities and public technical sources.

## Changes and evidence

| Area | Confirmed defect / before | Change and verification |
| --- | --- | --- |
| Search routing | Explicit Chinese search/source requests could miss the gate; fictional project recaps could enter web retrieval. | Extend explicit intents, honor offline wording, suppress fictional recaps, remove query wrappers. Routing regressions and a live fictional recap passed without web search. |
| Search transport | Query braces could be interpreted as URI templates; reserved characters needed strict encoding. | Encode the query as one expanded URI variable. Tests cover Chinese, `C++`, `&`, `%`, and literal braces. |
| Relevance | Bing RSS returned unrelated pages even for short English queries. | Require meaningful subject anchors, reject unrelated results, allow one quoted recovery query within the original deadline, expose degradation reasons. Live `RFC 9110` search rejected unrelated results and admitted no verified evidence. |
| Provided links | Searching the instruction text and a 1-second/256-KiB effective extraction boundary prevented reading a public RFC page. | Read explicit public HTTPS sources through the existing URL/DNS policy; reject private destinations and disabled networking. A single result gets up to 3 seconds, multiple results retain 1 second each, and decoding is bounded to 2 MB with 12,000 characters for model context. The 1.19-MB RFC 9110 page then returned the correct title and source in a real Core turn. |
| Weather | Technical weather API questions entered the forecast shortcut; unscoped conversations inherited global location. | Exclude technical/offline requests; require an explicit configured location or narrow same-topic follow-up. Missing location asks a question without calling weather or naming the owner's place. Positive forecast/date controls still pass. |
| Conversation integrity | Accepting another input could advance the active graph leaf before its predecessor finished. | Queue execution by user/client/session and append the user node when that turn starts. Deterministic failure-then-next-input and independent-session controls verify reply and history binding. |
| SSE | A terminal transition could race the event snapshot/publication and end the stream too early. | Observe inactivity before the event read and require terminal publication before completing. Both race orders, failure/cancellation/deadline, retry and replay contracts pass. |
| Facts and preferences | Current question and historical assistant-derived values lacked a distinct precedence boundary. | Mark the current input once; give latest applicable user corrections priority, require recalculation, separate topics, and honor current address/care preferences. This is prompt guidance, not a deterministic arithmetic or preference engine. |
| Training | Normal chat could be sampled and archived despite `training_mode=false`. | Ordinary chat never generates comparisons, emits candidate events, or archives observations. The legacy normal-probability setting remains accepted but has no sampling effect. Explicit training capture/selection/recovery and formal-memory authority stay separate. |

## Automated verification

Commands run from the repository root, except Maven from `java/meguri-core`:

| Command | Result |
| --- | --- |
| `mvn -o -B verify` | Latest full run: 697 tests, 0 failures, 0 errors, 5 skipped. Packaging initially failed because the isolated test JVM held the JAR open on Windows. |
| `mvn -o -B -DskipTests package` | Passed after stopping only that candidate JVM. Final candidate runs a copied JAR to avoid a build lock. |
| `mvn -o -B -Dtest=TurnJournalPersistenceContractTest test` | 5 passed, including an additional race-order variant added after the full run. |
| `python -m unittest discover -v` | 384 passed. |
| `pnpm test:ts` | 52 passed. |
| `pnpm test:adapter-contract` | DTO/schema consistency, Node adapter fixtures and 34 AIRI Vitest tests passed. |
| `pnpm --filter @meguri/desktop-meguri run test:unit` | 66 files / 483 tests passed. |
| `pnpm typecheck:desktop` | Passed. |
| `pnpm build:desktop` | Passed; existing large-chunk advisory remains. |
| `pnpm --filter @meguri/desktop-meguri run test:chat-interaction` | Passed in an isolated offline Electron profile; hover/cursor recovery, hit regions, repeated panel cycles and narrow header checks passed. |

Java tests use the repository launcher's short Unix-domain socket temporary-path workaround through
`-Djdk.net.unixdomain.tmpdir`; no OS network or security settings were changed.
The five skipped tests require Everything, Shizuki, a dedicated PostgreSQL recovery DSN (two tests),
or explicitly enabled public ModelScope import. None was enabled for this repair.

## Bounded real Core regression

A separate loopback Core on port 18089 used the installed launcher's Flash model and thinking settings,
an isolated temporary home, in-memory turn journal, synthetic identities, `formal_memory_allowed=false`,
and `training_mode=false`. The user's existing Core on port 18080 was kept running.

Twelve turns plus one affected-link recheck completed. Each observed terminal event matched its turn ID,
stream text matched the corresponding final snapshot, and no training event was emitted.
The candidate's isolated data directory contained metrics only, with no training archive.

- Budget correction: `735 - 128 = 607` followed by `800 - 128 = 672`; final two-project summary retained
  800/128/672 and 560/90/470 separately.
- Preference: no nickname, sleep reminder, or rest reminder in the two following story-writing turns.
- Missing-location weather request asked for a location without weather tool execution.
- Offline weather API parsing question received a technical answer without forecast execution.
- Fictional “latest model” recap skipped the web lane.
- Unreliable Bing results produced `web_results_irrelevant` and `web_search_retried`, not false citations.
- First direct RFC read failed; after the measured size/time fix, the recheck returned
  `RFC 9110: HTTP Semantics` with `https://www.rfc-editor.org/rfc/rfc9110.html` and no retrieval degradation.

These are bounded observations, not a new 301-turn pass rate. One search-failure reply still added an
unnecessary “try again in daytime” suggestion in a separate session without the explicit preference.
Broad naturalness, arbitrary arithmetic, topic transitions and long conversations therefore remain probabilistic.

## Skills, desktop and remaining limits

The existing Core returned an empty `/internal/v1/skills` catalogue, and the default local package root
contained no installed skill packages. Code uses an empty in-memory catalogue by default and does not
auto-import repository or Codex skills. No independent evidence established a loader or display defect;
no skill was downloaded, installed or enabled. Desktop event callbacks and snapshots already select the
created turn ID; those guards and existing client tests were retained.

Remaining limits:

- Bing RSS itself can still produce irrelevant results. Filtering and bounded retry improve honesty,
  but cannot guarantee search availability or completeness. Lexical checks can reject valid paraphrases.
- Weather supports explicit selection of the configured location; this change adds neither geocoding
  nor a scoped location-preference store. Other places require future location support.
- Queue ordering is enforced inside one Core runtime. Multi-process PostgreSQL recovery was not run.
- No compression-limit, real long-term-memory write, real installed-skill execution, or end-to-end
  live UI submission claim is made. Offline desktop interaction smoke is a separate result.
- Normal-mode training capture is now disabled; turn/chat history and explicit training feedback remain
  distinct from formal-memory writes. This change does not erase existing records.

## Local evidence and exclusions

Detailed synthetic probe data, public-search samples and the baseline audit remain local under
`reports/dialogue-repair-20261028/`. Original evaluation data remains under
`reports/dialogue-eval-20261028/`. Test logs are under `tmp/`; neither raw report data nor runtime artifacts
are added to Git.

The five pre-existing excluded files remain untouched and uncommitted:

- `docs/native-companion-appearance-feedback-2026-10-05.md`
- `docs/native-companion-feedback-2026-10-05.md`
- `docs/native-memory-server-feedback-2026-10-06.md`
- `docs/research/longmemeval-ccby-excerpt-2026-10-12.txt`
- `docs/training-smoke-attempt-2026-10-08.md`
