## Context

See proposal.md for motivation. `generate_bilibili_browser_report.py` deliberately emits metadata only. `UnavailableBilibiliSelectedVideoSummaryGateway` does no extraction, and the installed BHF MCP registers only watch-history tools. The daily PowerShell runner publishes the Python JSON and Markdown directly; the AstrBot card accepts additive fields within `bilibili_daily_v1`.

## Goals / Non-Goals

**Goals:** Add a usable daily content path without changing the Java metadata endpoint or installing a separate transcription stack. Reuse the existing Python runtime, external key files and scheduler.

**Non-Goals:** Account cookie access, automatic media download/ASR, interpreting playback progress as precise attention or learning, long-term personal profiling, and automatic remote deployment or chat delivery during verification.

## Decisions

1. Add `tools/enrich_bilibili_report.py` as a post-processing CLI between history generation and publication. It updates JSON/Markdown atomically and uses the existing visual payload builder. Keeping extraction separate preserves the independently tested metadata-reader boundary and limits changes to unrelated in-progress work.
2. Use a small standard-library public Bilibili adapter for descriptions and available WBI player subtitles. Imported transcript JSON provides an offline interface for exports from BiliNote or other tools. No account credentials are read; restricted subtitles fall back explicitly. Do not assume anonymous subtitle access works for every video.
3. Analyze the highest-weight videos with a default limit of 12 and a bounded wall-clock budget. Reuse per-video evidence and summary caches under the user's private `.meguri/bilibili` directory. Cache keys include evidence digest, prompt version and configured model identity; failures are not cached as successful model output.
4. Submit one bounded batch to the existing OpenAI-compatible text endpoint configured through `MEGURI_LLM_BASE_URL`, `MEGURI_LLM_MODEL` and external `MEGURI_LLM_API_KEY_FILE`. The runner accepts the already established key-file paths. A system instruction treats source text as untrusted evidence, requires specific topics, and forbids claiming learning or agreement. Validate identifiers, response shape and output limits. Aggregate directions deterministically from validated per-video topics and preserve evidence links. Without a model, show description excerpts or explicitly title-based content with a clear analysis status.
5. Add `content_analysis` and per-video insight fields. Retain the visual schema/template identifier and legacy `top_commentary` key for transport compatibility, but render it as concrete content for enriched reports. Cover all genres; show concise evidence labels and coverage, leaving full details in Markdown. Publishing uses an enriched title when the new field is present.
6. Use explicit template text rectangles and measured font ink bounds for positioning. Center each direction block vertically and horizontally in its pill, align subject baselines above the dotted rules, and bound the summary above the bottom decoration. This corrects the original font-origin offset and hand-estimated pill centers without changing the template asset.

## Risks / Trade-offs

- [Bilibili subtitle/login restrictions or API changes] → bounded failure with explicit description/title fallback and optional local transcript input; do not add bypass logic.
- [Model inference is imperfect] → per-video evidence labels, attributable links, validated membership and conservative prompt; no assertion that content was learned or endorsed.
- [Only a sample can fit the budget] → visible analyzed/total and evidence coverage; no full-day conclusion hidden behind partial data.
- [Existing card is compact] → three representative contents and concise directions on card; complete analysis in Markdown.
- [External content model request] → reuse the configured trusted text endpoint and external key file; no cookies, raw history database, account identifiers or watch timestamps are sent.

## Migration Plan

Run focused fixture tests and render a synthetic card. Enable the enrichment step in the local runner with a `-MetadataOnly` rollback switch. Existing scheduler executions use the script's new default without task re-registration. Remote AstrBot needs the updated renderer package deployed separately; do not send test messages to the user during validation.
