## Context

See proposal.md. The existing gateway runs before AstrBot's LLM and optionally owns all ordinary private/group messages. Its working tree already includes plugin-priority and owner Todo support. AstrBot v4.28.1 exposes `conversation_manager.get_curr_conversation_id/get_conversation` and `persona_manager.resolve_selected_persona`; the latter implements forced session overrides and runner-specific defaults.

## Goals / Non-Goals

**Goals:** Match the persona that AstrBot would use, release native messages without touching their event state, and keep existing deployments compatible until operators configure the feature.

**Non-Goals:** Deploy/restart production, invent persona IDs, change Core characters, merge AstrBot and Core histories, or change background reminder delivery.

## Decisions

1. Add `persona_routing_enabled=false` and `meguri_persona_ids=[]`. Matching uses exact trimmed IDs, not display-name guesses or fuzzy aliases. A separate opt-in switch preserves existing configurations; an enabled empty list means native chat for all ordinary messages.
2. Put persona lookup behind a small dedicated policy. Read the current conversation for `event.unified_msg_origin`, then delegate effective selection to AstrBot's native resolver. Do not reproduce its forced/default precedence or cache persona results. No conversation creation or history write is required.
3. Run the persona gate after plugin ownership and before natural-language Todo drafting. Native personas must not acquire Meguri actions or rendering metadata. Explicit `/meguri` commands and existing explicit service commands retain their existing paths. Automatic routing, sender checks and group restrictions continue to govern Core eligibility.
4. If the host lacks the resolver, returns no persona, or raises during lookup, release to AstrBot and log only the exception type. Do not fall back to all-message Core routing on lookup errors. The gateway never flips an event's pre-existing LLM-suppression flag when releasing it.
5. Add focused policy and real-entrypoint tests, then run the existing AstrBot suite and packaging checks. Build the current upload archive in ignored output. Stage only this change's additions to previously dirty tracked files; retain all unrelated edits unstaged.

## Risks / Trade-offs

- [AstrBot API drift] -> Use the native resolver verified against v4.28.1, test its call contract, and release native chat on unsupported versions.
- [Extra storage reads] -> Look up only when opted in with a nonempty list; avoid caching so switches apply immediately.
- [No default LLM configured] -> Native messages depend on the operator's existing AstrBot model configuration; the gateway does not fabricate a reply.
- [Two independent context stores] -> Document that selecting a backend does not migrate history or formal memory.

## Migration Plan

Build and inspect the plugin ZIP locally. For a later authorized deployment, install the package, enable persona routing, and enter the actual AstrBot IDs for the desired Meguri personas. Keep the existing automatic route flags enabled for the desired chat types. Disable `persona_routing_enabled` to restore legacy routing. This implementation does not change live configuration.

API evidence: [AstrBot v4.28.1 persona manager](https://github.com/AstrBotDevs/AstrBot/blob/v4.28.1/astrbot/core/persona_mgr.py) and [conversation manager](https://github.com/AstrBotDevs/AstrBot/blob/v4.28.1/astrbot/core/conversation_mgr.py).
