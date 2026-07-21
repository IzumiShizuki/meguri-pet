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

The Java runtime includes an opt-in Open-Meteo integration for the desktop
home. It stores only a user-selected label, coordinates and IANA timezone in
`%USERPROFILE%\.meguri\weather-location.json`; no device geolocation or API
key is used. When enabled, the runtime refreshes once during the saved
location's 02:00 hour and polls at a bounded interval for rain within the next
two hours. The desktop home reads the briefing when it opens, speaks it, and
can show a browser notification for a new rain window.

The default mock profile remains offline. Opt in explicitly before starting:

```powershell
$env:MEGURI_WEATHER_ENABLED = 'true'
$env:MEGURI_WEATHER_LOCATION_NAME = '上海'
$env:MEGURI_WEATHER_LATITUDE = '31.2304'
$env:MEGURI_WEATHER_LONGITUDE = '121.4737'
$env:MEGURI_WEATHER_TIMEZONE = 'Asia/Shanghai'
& 'D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd' -B spring-boot:run
```

The location can then be changed and saved from the desktop page. The runtime
must remain running for the 02:00 refresh and rain polling to occur. Override
`MEGURI_WEATHER_POLL_DELAY_MS`, `MEGURI_WEATHER_RAIN_THRESHOLD` or
`MEGURI_WEATHER_RAIN_LOOKAHEAD_HOURS` for a different reminder policy.

## Prefix input contract

The desktop home uses the loopback `POST /v1/input/resolve` endpoint before a
turn is submitted. This is a deterministic router, not another model call:

- ordinary text is passed to Meguri unchanged;
- `#` selects an allow-listed action: `#天气`, `#搜索 <关键词>`, or `#帮助`;
- `~<工程描述>` generates an editable engineering-task draft, but never calls
  Codex, changes code, or invokes a tool automatically;
- `##` and `~~` escape a literal prefix for normal dialogue.

The desktop page displays a `~` draft and requires a second send action after
the user reviews or edits it. Add a new `#` command only together with its
explicit execution boundary and tests; unknown commands fail closed.
