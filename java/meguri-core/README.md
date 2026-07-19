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
