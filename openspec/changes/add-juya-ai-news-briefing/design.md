## Context

See proposal.md for motivation. AstrBot routes character chat to the remote Java Core; the native desktop uses a local Java Core. Core already persists generic daily reports, and the AstrBot gateway polls them with durable per-target deduplication. The native desktop discovers generated reports in the project reports directory. Hosted models are instantiated by LlmProviderFactory from external credential files.

## Goals / Non-Goals

**Goals:** Share the dated bulletin between delivery and model context; keep network work off chat requests; enable the user's existing deployments.

**Non-Goals:** WeChat login/scraping, a second AstrBot installation, treating news as autobiographical memory, and automatic upstream claim verification.

## Decisions

- Put polling and generation in a Core AiNewsService, using a bounded RSS reader and the configured LangChain4j model. This works while the Windows desktop is stopped and avoids two competing summary histories inside AstrBot.
- Persist kind `ai_news` in DailyReportStore. Cache hits skip model calls; retain existing report delivery deduplication and targets. Add an authenticated latest/status API, and write local Markdown artifacts when a configured desktop report directory exists.
- Read the small latest persisted briefing into both provider context paths through a bounded optional lane. Mark it external reference data with publication date and source; never append articles to system instructions or long-term personal memory. Keep recent context available up to three days.
- Poll once on startup, then every ten minutes, using Asia/Shanghai explicitly and the 08:00 gate. Network/model errors are logged without secrets and leave the last successful report untouched.
- Use the existing remote Docker TLS administration path, create backups before replacing the remote Core artifact/config, and add `ai_news` to the gateway's existing daily-report kinds. Preserve other plugin sources and configuration; no unnecessary plugin installation.
- Import non-secret `ai-news.properties` from the writable daily-report directory; the existing model-data mount is read-only. Deploy only the selected feature classes over the old remote JAR, and preserve the same overlay in a versioned Docker image and the existing recreation tag.

## Risks / Trade-offs

- RSS publication can lag WeChat → clearly identify RSS as the source and use polling rather than claim instant delivery.
- Feed/model outage → retain the last dated report and retry; no false claim of today's news.
- Long or hostile content → disable XML external entities/DOCTYPE, cap response/content sizes, put article text in a user-data message, constrain summary output.
- Remote restart affects ongoing requests → test/package first, back up artifacts/config, restart only verified Meguri/AstrBot processes, check health and rollback if unsuccessful.

## Migration Plan

Implement and validate targeted Java/gateway tests; build the JDK 21 artifact; enable the local launcher and refresh the verified local Core; back up and configure the remote deployment and AstrBot; confirm generation, both latest-report endpoints, conversational context and local notification. Preserve OpenSpec change without archiving unless requested.
