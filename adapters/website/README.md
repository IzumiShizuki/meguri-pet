# Website adapter

The website adapter connects a browser UI directly to the local Meguri core. It does not route character turns through AstrBot and does not contain role, expression or memory policy.

The host website must inject a trusted bound `meguriUserId` plus a non-sensitive `storageKey`. Only the generated website session ID and an active turn ID are persisted. User identity is never accepted from chat input or written to browser storage by this adapter.

Phase 1 defaults to a loopback core URL. A non-loopback URL requires an explicit opt-in and is not a production authorization design.

Each browser runtime negotiates `/v1/hello` before its first Turn and sends the
canonical nested identity DTO. The selected protocol minor and server
capability revision are stored alongside the session checkpoint, without
persisting the Meguri user identity. SSE cursor expiry is restored through the
authoritative session snapshot, and the snapshot/checkpoint is persisted before
page-side ONCE effects run.
