## Context

See proposal.md for motivation. CompanionDirector has delivery gates and a playback ledger, but its scheduler only drains existing notices and confirmed commitments. Core owns conversation history; using the normal turn endpoint for a timer would invent user messages and expose tools. Eidolon-SC's independent reach-out generation and post-generation contact check inform this design; no upstream code is copied.

## Goals / Non-Goals

**Goals:** Add a bounded producer to the existing notice path and preserve assistant-only continuity after actual display.

**Non-Goals:** Multi-agent orchestration, remote message delivery, birthday/gift systems, full-duplex voice migration and long-term-memory extraction. The user subsequently authorized deployment to the hosted Core and the desktop, including live web retrieval acceptance.

## Decisions

1. Add POST `/v1/companion/reach-outs` and POST `/v1/companion/reach-outs/{id}/delivered`. Verify request scope through the existing identity verifier, and require `desktop_pet`. Read server-owned context, never accept caller-provided persona or arbitrary conversation. The gateway retains its current Core selection and credentials.
2. Add an optional LlmProvider hook. Mock providers decline. The real provider uses the same persona, a small JSON data envelope of recent messages, no tools, a bounded response, a non-blocking queue attempt and a timeout. Empty/`[SKIP]` output declines naturally.
3. Cache bounded short-lived draft attempts per scope/exchange, sharing concurrent requests. Successful acknowledgement appends an assistant node only if the original context leaf is still active, atomically and idempotently. Drafts never enter turn counters or memory extraction. This avoids normal-turn side effects.
4. Add an injectable renderer scheduler, created only by StageView. It checks idle/quiet/focus/consent/editing/visibility, waits afresh on mount, cancels when context changes, and rechecks eligibility after generation. A candidate is not queued to a later context. Delivery uses the existing notice ledger and mute policy; the displayed starter's message ID prevents another unanswered starter after reload. A content-free local-day/count budget is reserved under the same ledger lock, capped at three across conversations and stage restarts, independently of receipt pruning. Uncertain skipped claims conservatively consume a slot.
5. Keep existing initiative controls and clarify their behavior inline. Fixed night hours and bounded intervals avoid adding a second settings system. Network/health evidence belongs in the research report, separately from feature tests.

## Risks / Trade-offs

- Hosted Core may be an older version → 404/timeouts stay silent and retry only after backoff. Deploy and validate a candidate before activating the user-authorized update.
- Persona may encourage lengthy replies → enforce a short maximum and reject overlong/malformed output.
- Delivery/acknowledgement spans processes → acknowledge only after visible acceptance, retry a failed acknowledgement while the stage remains active, and reject changed context. A process crash between display and acknowledgement can leave only local display history.
- Fixed quiet hours follow the desktop timezone → document 22:00–08:00 and use injected local time in tests.
- Storage failures or uncertain delivery claims can suppress an otherwise eligible reach-out → fail closed, keep explicit reminders independent, and store only a local day/count without conversation content.

## Migration Plan

No schema migration or dependencies. Build/test Core and renderer together. Existing initiative off disables the producer; removing StageView scheduler wiring rolls back the feature while existing notices continue.

Deployment acceptance uses the current server container's environment, secrets and mounts without printing them; it retains the original container and image. A candidate uses a separate loopback port and unique network identity. Only after health and isolated model/web checks pass is the active container replaced. The local desktop continues to use its existing loopback gateway/Core and existing launcher configuration. Dedicated test sessions disable formal-memory writes and do not send real QQ/WeChat messages.

Live acceptance found `www.bing.com/search?format=rss` returning unrelated Japanese weather, hotel and school results for controlled Python documentation queries. The same local request against `cn.bing.com/search` returned Python/venv sources, including the official Python documentation. Correct only the installed launch/deployment endpoint and its configuration check; retain the provider implementation and generic application defaults. Re-run both paths and independently fetch cited public documentation. A positive result count alone is insufficient evidence of useful networking.
