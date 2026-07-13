# AstrBot Meguri gateway

This directory is the phase-1 offline gateway skeleton. It contains no AstrBot data, credentials, provider configuration, or production installation logic.

The adapter always declares `voice=false` and `screen_context=false`, hashes platform identifiers before sending them to the core, and separates private/group short-term sessions. Formal cross-client memory is shared only after identities are explicitly bound to the same `meguri_user_id`.

The default core endpoint is `http://127.0.0.1:8100`. This matches the existing production AstrBot host-network constraint without publishing Meguri on `0.0.0.0`. Production installation into `/opt/astrbot/data/plugins` remains approval-gated and is not performed by this code.
