## Why

The owner wants Meguri to read Juya's daily AI bulletin, send its key points automatically, and use those facts in subsequent conversations on both AstrBot and the local desktop. Independent AstrBot summary calls do not feed the shared Core conversation context.

## What Changes

- Add an opt-in RSS briefing service using Juya's public feed, the configured hosted model, durable daily summaries, bounded retries, and date-based deduplication.
- Publish AI news through the existing Core daily-report inbox and AstrBot delivery poller; expose the local bulletin as a desktop artifact.
- Supply dated, source-attributed news context to both legacy and structured model requests without treating articles as executable instructions or personal memories.
- Enable and verify the feature in the owner's existing remote AstrBot/Core and local startup configuration.

## Capabilities

### New Capabilities

- `juya-ai-news-briefing`: Polling, summarization, daily delivery, and bounded conversational access to Juya AI news across shared Core clients.

### Modified Capabilities

None.

## Impact

Java Core daily-report and LLM integration, local launcher configuration, AstrBot gateway deployment configuration, and owner-facing documentation. Reuse existing toolchains, credentials and messaging destinations. Existing uncommitted work must be preserved.
