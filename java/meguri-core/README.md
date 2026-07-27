# Meguri Core (Java)

This directory is an isolated Java 21 / Spring Boot 3.5 reactive module. It
provides the JVM boundary for the Meguri runtime while leaving the existing
Python and TypeScript implementations authoritative and untouched.

## Dependency baseline

- Spring Boot `3.5.15` with `spring-boot-starter-webflux` for Flux/SSE APIs.
- LangChain4j BOM `1.15.1`.
- The BOM's beta stream resolves the official Spring Boot 3 starters to
  `1.15.1-beta25`; a `1.15.1` starter artifact is not published separately.
- OpenAI integration is supplied by
  `langchain4j-open-ai-spring-boot-starter`; declarative AI services come from
  `langchain4j-spring-boot-starter`.

## Offline mock (default)

`src/main/resources/application.yml` intentionally contains no OpenAI model
credentials or endpoint. Starting the application with the default profile
does not make model network calls; runtime code should use its deterministic
mock provider until a real provider is explicitly enabled.

```powershell
$env:JAVA_HOME = 'D:\environment\jdk\temurin-21\jdk-21.0.11+10'
$env:Path = "$env:JAVA_HOME\bin;D:\environment\maven\runtime\apache-maven-3.9.16\bin;$env:Path"
& 'D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd' -B test
& 'D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd' -B spring-boot:run
```

## OpenAI-compatible endpoint (explicit opt-in)

Set the provider environment variables explicitly. The same properties work
with OpenAI and compatible gateways such as a local OpenAI API server or a
hosted provider:

```powershell
$env:MEGURI_LLM_PROVIDER = 'openai-compatible'
$env:MEGURI_LLM_API_KEY_FILE = 'D:\secrets\meguri-llm-api-key'
$env:MEGURI_LLM_BASE_URL = 'https://provider.example/v1'
$env:MEGURI_LLM_MODEL = 'model-name'
& 'D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd' -B spring-boot:run
```

Use an HTTPS base URL for non-loopback providers. Never commit API keys or put
them in this repository. `MEGURI_LLM_BASE_URL` should identify the provider's
OpenAI-compatible `/v1` API root; LangChain4j supplies the model transport and
operation paths. Remote providers require a key file; inline API keys are
rejected.

## Durable PostgreSQL profile

The default `local-mock` profile deliberately uses the process-local Turn
journal and Context persistence. To use the durable implementations, activate
the `postgres` profile and provide a PostgreSQL JDBC endpoint:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'postgres'
$env:MEGURI_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:5432/meguri'
$env:MEGURI_POSTGRES_USER = 'meguri'
$env:MEGURI_POSTGRES_PASSWORD = '<local-secret>'
& 'D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd' -B spring-boot:run
```

`PostgresTurnJournal` initializes the idempotent schema in
`src/main/resources/db/turn-runtime.sql`. It persists Turn snapshots, events,
session sequences and outbox rows; `PostgresSessionContextPersistence` stores
the complete message graph snapshot. This bootstrap is convenient for the
current single-service stage, but is not a replacement for a versioned
production migration process.

The current implementation does not dispatch `turn_outbox` rows. Delivery
acknowledgement, retry, cursor compaction and replay-gap snapshots remain open,
and the PostgreSQL path has not yet been proven on this workstation against a
real database or Testcontainers.

## Prompt token budget

OpenAI-compatible providers use LangChain4j's JTokkit-backed
`OpenAiTokenCountEstimator`. `MEGURI_LLM_TOKENIZER_MODEL` selects the tokenizer
mapping independently from `MEGURI_LLM_MODEL`; set it to a compatible known
model when a gateway exposes a custom model name. The system prompt and all
context lanes share `MEGURI_LLM_PROMPT_TOKEN_BUDGET` (default `12000`).

Optional lanes are reduced after the 70% precompression threshold with a 90%
hard target. The current implementation performs this work synchronously;
durable background summarization/precompression jobs remain open.

## Prompt-cache telemetry

The runtime does not cache complete Meguri replies. A reply depends on the
current relationship state, recent context, Lore RAG, long-term memory and web
results, so reusing a prior reply would bypass current context and memory
candidate handling. The stable system prompt is kept first in every request so
provider-side prefix/KV caches can reuse it.

For real providers, the Java runtime records content-free usage metrics:
prompt/output/total tokens, cache hit/miss tokens, failures, daily aggregates
and the SHA-256 of the current system prompt. DeepSeek's
`prompt_cache_hit_tokens` and `prompt_cache_miss_tokens` are read from the raw
OpenAI-compatible response retained by LangChain4j. No prompt, user message,
memory, RAG text or reply is written to the metrics file.

Local metrics are available at `GET /v1/runtime/metrics/prompt-cache` and are
persisted to `%USERPROFILE%\.meguri\metrics\prompt-cache.json` by default. To
publish snapshots to the administrator-only shizuki-site dashboard, configure:

```powershell
$env:MEGURI_PROMPT_CACHE_EXPORT_URL = 'https://api.shizuki.online/api/v1/internal/meguri/prompt-cache'
$env:MEGURI_PROMPT_CACHE_EXPORT_TOKEN_FILE = 'D:\secrets\meguri-metrics-token'
$env:MEGURI_PROMPT_CACHE_EXPORT_INTERVAL_MS = '60000'
```

The token file must contain the same secret configured as
`MEGURI_METRICS_INGEST_TOKEN` on shizuki-site. Remote export requires HTTPS;
loopback HTTP remains available for local integration testing.

## PNG standing-illustration runtime map

`ExpressionResolver` prefers `configs/meguri_sprite_runtime_map.json` when it
is present, or the path in `MEGURI_SPRITE_RUNTIME_MAP`. This reviewed runtime
map selects a shared expression code for each semantic expression and
intensity, then combines it with the current outfit to resolve the PNG name.
It is intentionally separate from the canonical dataset export and fails
closed on malformed codes or a build ID mismatch.

## Scope and validation boundary

This is a compileable integration skeleton, not a production deployment. It
does not provision PostgreSQL/pgvector, Redis, Kafka, MemoryOS, credentials,
or a hosted model. Hosted/staging readiness still requires an authenticated
smoke test and the repository's existing release gates.

## Controlled web search

The Java runtime follows AIRI's tool-boundary approach: search is a runtime
tool, not an implicit model permission. `WebSearchPolicy` only activates it
for explicit requests such as `联网搜索`, `查资料`, `最新消息` or `look up`.
The default configurable gateway is Bing RSS (`MEGURI_WEB_SEARCH_BASE_URL`),
with HTTPS-only transport, redirect following, an eight-second timeout and at
most five results. Only title, URL and summary are injected as `web_results`;
arbitrary URL fetching is not exposed. Set `MEGURI_WEB_SEARCH_ENABLED=false`
to keep the service offline.

## Local weather briefing and rain reminder

The Java runtime includes an Open-Meteo integration for the desktop
home. It stores only a user-selected label, coordinates and IANA timezone in
`%USERPROFILE%\.meguri\weather-location.json`; no device geolocation or API
key is used. When enabled, the runtime refreshes once during the saved
location's 02:00 hour and polls at a bounded interval for rain within the next
two hours. Weather and outing messages force one shared live refresh; outing
replies add a caring road-safety greeting even when the provider is unavailable.
On weekdays from 08:00 through 21:59, Java performs one check per hour and
publishes a desktop notice only when a hazard first appears or weather changes
materially. The desktop home reads the briefing when it opens, speaks it, and
can show a browser notification for a new rain window or hourly change.

Weather is enabled by default for the Qiantang District center. These optional
overrides select a different location or explicitly disable the integration:

```powershell
$env:MEGURI_WEATHER_ENABLED = 'true'
$env:MEGURI_WEATHER_LOCATION_NAME = '浙江省杭州市钱塘区'
$env:MEGURI_WEATHER_LATITUDE = '30.323040'
$env:MEGURI_WEATHER_LONGITUDE = '120.493941'
$env:MEGURI_WEATHER_TIMEZONE = 'Asia/Shanghai'
& 'D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd' -B spring-boot:run
```

Set `MEGURI_WEATHER_ENABLED=false` only when weather access should be disabled.

The location can then be changed and saved from the desktop page. The runtime
must remain running for the 02:00 refresh and rain polling to occur. Override
`MEGURI_WEATHER_POLL_DELAY_MS`, `MEGURI_WEATHER_RAIN_THRESHOLD` or
`MEGURI_WEATHER_RAIN_LOOKAHEAD_HOURS` for a different reminder policy. Work
hours can be changed with `MEGURI_WEATHER_WORK_START_HOUR` and
`MEGURI_WEATHER_WORK_END_HOUR` (end-exclusive).

## Local resource references with Everything

Typing `@<keywords>` in the desktop home opens a metadata-only resource picker.
Dropping a file into the Electron pet uses the same Everything validation and
attachment contract; a normal browser page falls back to the `@` picker.
The Java runtime calls voidtools' official `es.exe` over the already-running
Everything IPC service; it does not enable Everything HTTP or ETP. Results are
limited to configured roots, real paths are checked again, and common secret,
credential, VCS, dependency and build paths are filtered before display.

Selection is explicit and still requires the user to send the turn. The LLM
receives at most the selected file names and kinds plus `content_access=not_read`;
it never receives the local path or file contents, and that turn is excluded
from long-term memory writes. Set `MEGURI_EVERYTHING_ALLOWED_ROOTS` to a
semicolon-separated allow-list to replace the defaults (`D:\program` and the
current user's Desktop, Documents, Downloads, Pictures, Videos and Music).

## Bilibili account metadata digest

`#视频日报` first connects to BilibiliHistoryFetcher's loopback, read-only MCP
and calls only `query_history_records` for the requested calendar date. Meguri
never receives `SESSDATA`, never reads BHF's raw account database, and never
downloads pages, subtitles or media. The MCP Bearer token is read from a file
outside this repository (default `${user.home}/.meguri/bilibili/mcp-token.txt`)
and the adapter rejects non-loopback URLs and HTTP redirects.

If MCP is unavailable, the report explicitly falls back to local Chrome/Edge
`History`; it does not merge both sources. JSON and Markdown identify
`data_source` as `account_mcp`, `browser_history_fallback`, or
`browser_history`, and state the corresponding data boundary. The account
report adds only metadata such as title, UP, category, progress and duration.
Every video's `content_summary_status` remains `not_requested`.

An independent Windows task can run the synchronization and report pipeline
while Java Core is down. Install it only after an interactive QR login and a
successful manual account-MCP report:

```powershell
# Inspect without changing Task Scheduler.
D:\program\meguri-pet\ops\scripts\install-bilibili-daily-task.ps1 -Preview

# Install for the current interactive Windows user.
D:\program\meguri-pet\ops\scripts\install-bilibili-daily-task.ps1

# Keep the loopback read-only MCP available while this user is signed in, so
# an on-demand #视频日报 uses the account source instead of browser fallback.
D:\program\meguri-pet\ops\scripts\install-bilibili-readonly-mcp-task.ps1
```

The daily task triggers at 02:20 with a 20-minute random delay, so it starts between
02:20 and 02:40 local time. It synchronizes the previous completed calendar
day, stops synchronization at 02:50, starts the read-only MCP by 02:53, stops
report generation at 02:58, and treats 03:00 as the hard deadline. The task
state is written outside Git to
`${user.home}/.meguri/bilibili/state/meguri-daily-latest.json`. Browser fallback
is marked `degraded` rather than successful by the scheduled runner. The task
can wake a sleeping computer, but its `Interactive` logon mode means Windows
must have the user's signed-in session available; a powered-off computer does
not run it. The separate read-only MCP task starts at current-user logon, binds
only `127.0.0.1:8899`, and is hidden; it does not synchronize, write history,
or expose Bilibili credentials. With no date parameter, `#视频日报` uses the
previous completed calendar day.

After a `ready` or `empty` report is generated, the runner writes the bounded
desktop notice `reports/daily/latest.json` and uploads the validated envelope
to `POST /v1/daily/reports`. The public upload reuses the token file configured
by `CoreTokenFile`; the remote Core keeps complete Markdown under
`MEGURI_DAILY_REPORT_DIR`. AstrBot polls `GET /v1/daily/reports/latest`, while
both desktop and AstrBot persist the last delivered report ID so a restart does
not duplicate that day's notice. A remote upload failure leaves the local
report and desktop notice intact and is recorded as a degraded publication.

The report generator can also be invoked directly:

```powershell
D:\environment\anaconda3\envs\py314\python.exe `
  D:\program\meguri-pet\tools\generate_bilibili_browser_report.py `
  --project-root D:\program\meguri-pet `
  --date 2026-07-22 --timezone Asia/Shanghai `
  --mcp-url http://127.0.0.1:8899/mcp/ `
  --mcp-token-file "$HOME\.meguri\bilibili\mcp-token.txt" `
  --sync-status-file "$HOME\.meguri\bilibili\state\daily-sync-latest.json"
```

The optional sync-state file requires an explicit `target_date` matching the
report date and accepts only `success`,
`risk_stop`, or `error`; a missing, invalid, or different-date state is reported
as `stale`, while an omitted option is `not_checked`. This keeps a readable old
MCP snapshot from being mislabeled as a successful current-day sync. Exit code
`0` means a report was generated from account MCP or a browser fallback; exit
code `2` means every source was unavailable. The stdout JSON always carries
the exact `data_source`, `sync_status`, sources and boundary.

In the Electron pet, the Markdown artifact opens with the Windows default
application through a path-allow-listed IPC handler. In a normal browser, its
loopback HTTP artifact opens in the default browser instead.

The implementation uses the existing `py314` environment and Python standard
library only. Override `MEGURI_BHF_MCP_URL`, `MEGURI_BHF_MCP_TOKEN_FILE`,
`MEGURI_BHF_SYNC_STATUS_FILE`, `MEGURI_CHROME_HISTORY`, or
`MEGURI_EDGE_HISTORY` as needed, or disable it with
`MEGURI_BILIBILI_REPORT_ENABLED=false`. `BilibiliSelectedVideoSummaryGateway`
and its request/result DTOs reserve a content-summary boundary for one to five
explicitly selected BV ids; the current implementation returns `unavailable`
and performs no content access. The explicit gate is
`POST /v1/daily/bilibili/selected-summary`; it is never called by the daily
pipeline and rejects an empty selection.

## Prefix input contract

The desktop home uses the loopback `POST /v1/input/resolve` endpoint before a
turn is submitted. This is a deterministic router, not another model call:

- ordinary text is passed to Meguri unchanged;
- `#` selects an allow-listed action such as `#天气`, `#账单`, `#视频日报`,
  `#搜索 <关键词>`, or `#帮助`;
- `~<工程描述>` generates an editable engineering-task draft, but never calls
  Codex, changes code, or invokes a tool automatically;
- `@<关键词>` requests an Everything resource selection without reading it;
- `##`, `~~` and `@@` escape a literal prefix for normal dialogue.

The desktop page displays a `~` draft and requires a second send action after
the user reviews or edits it. Add a new `#` command only together with its
explicit execution boundary and tests; unknown commands fail closed.

## Sleep-time memory consolidation

The existing `sleep` mode only controls runtime tone, clothing and expression.
It does not train a model or alter canonical Lore RAG. The optional
sleep-memory job is a separate, auditable step: during the configured local
02:00 hour it snapshots eligible resident in-memory sessions, redacts credential-like
messages, and stores a bounded session summary through the token-protected
Python authoritative-memory bridge.

Enable it only after the Python memory bridge and its internal token are
configured:

```powershell
$env:MEGURI_SLEEP_MEMORY_CONSOLIDATION_ENABLED = 'true'
$env:MEGURI_SLEEP_MEMORY_TIMEZONE = 'Asia/Shanghai'
```

Use `POST /v1/memory/sleep-consolidation` for an explicit local run and
`GET /v1/memory/sleep-consolidation` for aggregate-only status. Session
summaries are not automatically promoted into long-term facts and never modify
the protected canonical RAG; durable facts still use the existing reviewed
memory-candidate workflow.
