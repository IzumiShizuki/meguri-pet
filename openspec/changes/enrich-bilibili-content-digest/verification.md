# Verification: enrich-bilibili-content-digest

Date: 2026-10-24 (Asia/Shanghai). Scope: local implementation, fixtures, public-text probe and a synthetic real-model request. No personal history was supplied to the model and no platform message was sent.

## Scorecard

| Dimension | Result |
| --- | --- |
| Completeness | 8/8 implementation tasks completed; all four added requirements mapped below |
| Correctness | 29 focused tests passed, including alignment regressions; runner parse/dry-run, live public text and configured model probes passed |
| Coherence | Independent content step, preserved metadata reader, additive publishing contract and external cache/key files follow design |

## Requirement and scenario evidence

| Requirement | Implementation | Validation |
| --- | --- | --- |
| Concrete subjects have attributable evidence | `tools/enrich_bilibili_report.py`: public evidence adapter, matching imported BV/CID, basis labels, source links and scoped excerpts | Subtitle identity/description fallback; rejected hosts; multipart fallback; imported CID mismatch; title-only fixture tests |
| Viewing directions are supported by analyzed videos | `enrich_report`: weight-prioritized sampling, model membership validation, direction-to-BV aggregation and coverage | Mixed Java/language/game subjects; explicit 1/3 sample and remaining unanalyzed statuses |
| Enrichment is bounded and resilient | `JsonHttp`, `PrivateCache`, model result validation and runner bounded process/fallback | Cache hit and changed-transcript invalidation; rejected unknown/duplicate BV IDs; model failure/recovery; expired budget; redirect rejection |
| Existing card and Markdown expose content | `content_markdown`, compatible visual payload, card renderer, enriched publisher labels | CLI → local mock model → enriched JSON/Markdown → publisher integration; old metadata/card/poller/package regressions; rendered card inspected |

## Checks performed

- `python -m unittest tools.tests.test_enrich_bilibili_report tools.tests.test_generate_bilibili_browser_report tests.test_bilibili_daily_card tests.test_daily_report_publisher tests.test_astrbot_daily_reports tests.test_astrbot_plugin_package`: **27 passed**.
- PowerShell AST parse: no errors. `run-bilibili-daily.ps1 -DryRun -TargetDate 2026-10-23` reports content enabled with a 12-video limit; `-MetadataOnly` reports disabled. No sync/publication ran during these dry-runs.
- Read-only scheduled-task inspection: `Meguri-Bilibili-Daily` is Ready and references `run-bilibili-daily.ps1`. No task was registered or changed.
- One public video probe obtained a description; anonymous subtitle availability was not established. No account data or cookies were read for this probe.
- One real request to the existing configured text model used three synthetic title/transcript records. All three returned summaries passed JSON/BV membership validation. Keys were read from an existing external key file and never printed.
- `reports/previews/bilibili-content-digest-demo/daily.png` rendered at 1086×1448 and was visually inspected after updating the date, content headings, wrapped summaries and direction labels. Its footer identifies synthetic data. Demo JSON/Markdown remain alongside it.
- `git diff --check` on the four modified tracked implementation files passed; Git's existing PowerShell line-ending notice is non-failing.
- `openspec validate enrich-bilibili-content-digest --strict`: passed.

## Findings and deployment limits

No critical implementation issues or spec/design divergences were found. Public subtitle availability depends on Bilibili restrictions; the shipped fallback and local transcript interface cover that known design limitation. Model quality is inherently interpretive; source labels, sample counts and original-video links remain visible.

The local daily script now uses the new content step. The remote AstrBot renderer was **not deployed or restarted** in this task. The updated `adapters/astrbot/astrbot_plugin_meguri_gateway/bilibili_daily_card.py` must be loaded there to obtain the new card headings and multi-line content layout. An older renderer still receives the compatible `bilibili_daily_v1` payload and the enriched overall summary. Full scheduled synchronization-to-QQ delivery was not exercised because it would send a real message.

OpenSpec change is implemented and verified; artifacts are retained, not archived.

## Alignment follow-up

The owner reported misaligned text in the first preview. The renderer now measures actual glyph bounds, compensates PIL font-origin offsets, centers whole tag blocks inside their actual template pills, and uses fixed rectangles for statistic values/notes, banner labels, footer and content rows. Long values shrink to fit; subject text stays above dotted rules; the summary is limited to six lines above the bottom decoration. Short subjects shrink slightly to avoid a lone trailing character on a second line.

Two regression tests record actual `ImageDraw.textbbox` results during complete renders: single-line and wrapped tags must be centered in all six pills; long subjects and summaries must remain in their allowed regions. The affected suite now passes **29 tests**. Both `daily-aligned.png` and `alignment-long-text.png` under `reports/previews/bilibili-content-digest-demo/` were rendered and visually inspected; `daily.png` was refreshed. These remain synthetic previews, and remote deployment status is unchanged.
