# Meguri Shared Adapter Protocol v1

`adapter-protocol.schema.json` is the canonical wire DTO source. The TypeScript
parsers validate against its `$defs` before returning the corresponding public
types.

## Endpoints

| Method | URL | Purpose |
| --- | --- | --- |
| `POST` | `/v1/hello` | Select protocol minor, capabilities, extensions, and server-granted permissions |
| `POST` | `/v1/turns` | Create one asynchronous Turn; accepts `Idempotency-Key` |
| `GET` | `/v1/turns/{turn_id}` | Query that same Turn |
| `POST` | `/v1/turns/{turn_id}/cancel` | Request cancellation of that same Turn |
| `GET` | `/v1/sessions/{session_id}/events` | Subscribe with `after_sequence` and `Last-Event-ID` |
| `GET` | `/v1/sessions/{session_id}/snapshot` | Fetch authoritative state and current sequence |

The URL fixes major version 1. Every DTO or event envelope carries
`protocol_version: "1.x"`. A non-1 major fails with
`UNSUPPORTED_PROTOCOL_MAJOR`. Unknown optional fields from a newer minor are
ignored. A named `required_extension` fails with
`UNSUPPORTED_REQUIRED_EXTENSION` unless it was negotiated in hello.

## Identity And Trust

`meguri_user`, `platform_actor`, `client_instance`, and `session` are separate
identities and must not be collapsed:

- `meguri_user` is the server-owned Meguri account/person identity.
- `platform_actor` is the actor identity supplied by the hosting platform.
- `client_instance` identifies one adapter installation, process, browser tab,
  or device and declares its adapter profile.
- `session` identifies conversational continuity.

Client capabilities describe what the client can render or provide. Client
permissions are requests, not grants. The server-authenticated permission set is
authoritative, and negotiation only intersects it with the client request.

## Delivery And Replay

Business events have a stable `event_id`, monotonically increasing
session-scoped `sequence`, `created_at`, and one replay policy:

- `STATE` reconstructs authoritative state and is safe to replay with event ID
  deduplication.
- `ONCE` represents TTS, animation, notification, or another side effect.
  Servers retain its stable event ID; clients checkpoint it before dispatch and
  never dispatch it twice after reconnect.
- `ALWAYS` may be emitted again as a new occurrence. At-least-once transport
  duplicates that retain the same event ID are still deduplicated.

Heartbeat is transport liveness only. It has no business `sequence` and cannot
advance the session cursor.

If an event cursor is outside retention, the server returns HTTP 410 with
`CURSOR_EXPIRED`. The client fetches the session snapshot, restores its sequence
and processed ONCE IDs, then resumes. If SSE is unavailable, the client polls
that same snapshot. A synchronous compatibility surface may only wait for the
terminal state of the already-created asynchronous Turn; it must not create a
second request model or execution path.

Stable errors use `ProtocolError.code`, `retryable`, and optional `details`.
Human-readable `message` text is not a machine contract.
