## Why

The desktop already delivers news, reminders and confirmed follow-ups, but never produces an idle conversation starter. The interrupted Eidolon-SC comparison requested this missing companion behavior while retaining Meguri's existing story, memory and dialogue systems.

## What Changes

- Generate short, optional reach-outs from the existing persona and recent completed conversation through a separate, tool-free Core endpoint.
- Schedule idle attempts using the existing initiative, focus and speech controls; respect quiet hours, consent, active conversations, cooldown and one unanswered reach-out per interaction.
- Deliver through the existing notice ledger and acknowledge only displayed messages into assistant context, without creating user turns or memory candidates.
- Record a source-backed comparison and actual network/runtime checks.
- Deploy the server Core and local desktop update, preserving rollback assets, and verify real model-backed web retrieval through the active paths (subsequently authorized by the user).

## Capabilities

### New Capabilities

- `idle-companion-reach-outs`: Grounded, bounded and interruptible idle conversation starters.
- `deployed-web-search-acceptance`: Deploy the validated search endpoint and verify relevant, accessible sources through the selected runtime paths.

### Modified Capabilities

None.

## Impact

Java Core LLM boundary, companion controller/service and assistant context append; native desktop notice policy and stage scheduler; companion settings explanation; regression tests, deployment and research documentation. No new dependencies, multi-agent runtime, or automatic long-term memory writes.
