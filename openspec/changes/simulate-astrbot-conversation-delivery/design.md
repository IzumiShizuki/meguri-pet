## Context

See proposal.md. The repository already contains AstrBot fixtures, a real HTTP/SSE client, reminder draft handling and extensive unrelated work. Live logs proved server Web lookup is disabled and historical text promised unverified reminders. No real QQ destination is needed for the requested tests.

## Goals / Non-Goals

Goals: cover the production adapter path with observable HTTP requests, isolated synthetic conversations and an actual local receiver; provide a repeatable command and separate real-Core findings.

Non-goals: emulate the full QQ SDK, alter production routing/credentials, enable server features, deploy changes or infer device read receipts.

## Decisions

1. Use a minimal AstrBot SDK shell only at the platform boundary; import and instantiate the real plugin. Existing tests replace the gateway, which cannot detect protocol interactions.
2. Bind a standard-library threaded HTTP fixture/collector to 127.0.0.1:0. Let the OS reserve the chosen port through the listener lifecycle rather than scanning for a port and reopening it.
3. The fixture implements hello, turn acceptance, bounded SSE and action/report endpoints with deterministic scripted outcomes. Capture request identity, session and write counts; never infer an action from reply text.
4. Submit returned plugin message chains to the collector and retain its acknowledgement separately. This is local collector acceptance; renderer/QQ delivery remains a different test layer.
5. Support an explicit loopback Core URL plus an external token file for optional real-Core runs. Existing SSH forwarding may be used during acceptance without embedding SSH credentials in the tool. Keep the collector local and use unbound synthetic identities so formal-memory writes remain disabled.
6. Tests cover private/group separation, routing ownership, duplicates, SSE reconnection, structured retrieval outcomes, Core/collector failures and private reminder draft/confirm/cancel/group restrictions. The live report flags missing action receipts and evidence limitations separately from successful response transport.
7. The first simulation exposed the existing numeral parser rejecting a standalone 十 before reaching its tens branch. Handle that bounded numeral explicitly; retain the end-to-end reminder cases as regression evidence.
8. The configured Core replayed a previous turn on the second request; the Python client rejected it before advancing the session cursor. The shared protocol defines session-scoped sequences, so keep strict session validation and normal sequence/replay validation, then use the existing per-turn filter before applying semantic/runtime/terminal fields. Model the fixture's sequence/history at session scope and retain cross-session rejection tests.

## Risks / Trade-offs

- Fixture success may hide model/configuration defects → label fixtures and run real-Core probes separately, retaining raw public outputs and findings.
- Importing SDK shells may affect other tests → restore module registry entries on cleanup and avoid production SDK installation.
- Test state may overwrite user state → use TemporaryDirectory and synthetic sessions; no existing checkpoint or target files are opened.
- Socket or background task leaks → explicit context managers and async cleanup, bounded HTTP waits, listener shutdown/join on all paths.

## Migration Plan

Add support code, tests and runner; run focused and complete Python suites; run isolated real-Core probes through a temporary loopback forward when available. Document evidence and limits, validate OpenSpec, keep the change unarchived.
