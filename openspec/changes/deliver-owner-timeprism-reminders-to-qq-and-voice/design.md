## Context

The native desktop already polls the connected Shizuki TimePrism Todo API and produces one local event per occurrence. Its main process owns the encrypted Shizuki refresh token and notification. The renderer has a local GPT-SoVITS playback path. AstrBot already sends proactive daily reports by polling the remote Meguri Core, but there is no reminder queue. See proposal.md for motivation.

## Goals / Non-Goals

**Goals:** Keep TimePrism authoritative for Todo data, bind outgoing QQ reminders to a single account, tolerate transient network/QQ outages, and retain the existing desktop card independently of speech/QQ failures.

**Non-Goals:** Server-side scheduling while the desktop is stopped; using QQ as a Todo store; sharing Shizuki credentials with AstrBot; exposing an arbitrary QQ send API to renderer or model tools.

## Decisions

### Owner binding

Store one selected Shizuki user ID in desktop user data. Selection requires a connected account and is explicit in settings. All local enhanced reminder events compare the current authenticated user ID with the selected ID before enqueueing QQ. AstrBot config independently pins that same Shizuki ID and one `FriendMessage` origin. This avoids treating ADMIN group membership or daily-report targets as proof of ownership; those alternatives can include multiple people.

### Desktop speech

Use the renderer's existing `playSpeech` method for a short reminder sentence, gated by the existing speech preference. Deliver the card before starting speech. Audio failure is isolated. The reminder event is emitted from the trusted main process and is not model-generated.

### Remote transport and storage

The Electron main process keeps an atomic local outbox keyed by a deterministic occurrence ID and posts to a fixed HTTPS remote Core reminder endpoint using the existing file-backed desktop Core bearer token. It sends the account ID, bounded title, due/trigger timestamps, and occurrence ID; no Shizuki token or target address crosses this boundary. The remote Core validates, idempotently stores events in a durable bounded inbox, and offers cursor polling to authenticated AstrBot. The desktop removes an outbox item only on a successful Core receipt; retry uses exponential backoff.

Core's existing shared token and identity headers are used, but the endpoint additionally requires `desktop_pet` for upload and `astrbot` for polling. Hosted deployments must configure a dedicated main Shizuki user ID before accepting or returning reminders. The server records only sanitized text and event metadata. The AstrBot plugin accepts one exact `FriendMessage` origin from config and never reads a destination from an event.

### Delivery semantics

AstrBot polls the Core feed with a persisted per-event delivery cursor. It sends in event order, advances only after `context.send_message` reports success, and retries after failures. Core upload idempotency prevents desktop retries from duplicating events. AstrBot delivery is at least once across an ambiguous crash during a QQ send; exact once cannot be guaranteed without a platform idempotency API. The UI distinguishes pending Core upload from confirmed QQ send, if a receipt signal is available; otherwise it describes QQ as configured/pending rather than confirmed.

## Risks / Trade-offs

- [Desktop stopped at due time] → The existing twelve-minute catch-up window applies; document this clearly and keep startup-at-login available.
- [Core accepted an event but response was lost] → Deterministic occurrence ID makes retry safe.
- [QQ send succeeded just before AstrBot crashed] → A retry may duplicate one message; document at-least-once delivery and consider platform idempotency later.
- [Shizuki production has not deployed zero-minute offsets] → The desktop may receive a validation error for newly created at-time Todos until that companion release ships; this change does not hide that error.
- [Remote configuration is absent] → Desktop voice/card still work, and QQ status remains visibly incomplete.

## Migration Plan

1. Deploy the Core inbox and configure its owner Shizuki ID, with the existing authenticated public reverse proxy.
2. Configure AstrBot's owner user ID and exact QQ private message origin, then deploy the plugin.
3. Start the updated desktop, connect the owner's Shizuki account, and select it as the main account in settings.
4. Create a near-term Todo in TimePrism and confirm desktop card, spoken playback, Core receipt, and a single QQ message. Rollback disables the AstrBot poller; stored Todos remain in Shizuki.
