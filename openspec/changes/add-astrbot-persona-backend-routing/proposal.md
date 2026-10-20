## Why

AstrBot's all-message gateway currently sends ordinary chat to Meguri regardless of the selected AstrBot persona. The owner needs Meguri personas and AstrBot's native personas to coexist in the same bot.

## What Changes

- Add an optional persona-routing switch and an exact list of AstrBot persona IDs that use Core.
- Resolve the effective persona through AstrBot's conversation and persona managers for each ordinary message, including default and forced session rules.
- Leave other personas and unresolved persona lookups with AstrBot's normal pipeline without suppressing its LLM or invoking Core.
- Preserve explicit `/meguri` access, existing sender/group authorization, plugin-command priority and legacy routing when the switch is disabled.
- Document configuration and build a reviewable plugin upload archive.

## Capabilities

### New Capabilities

- `astrbot-persona-backend-routing`: Select Meguri or native AstrBot processing using the effective persona of each chat.

### Modified Capabilities

None.

## Impact

The Python AstrBot gateway entrypoint, plugin configuration schema, a new persona-routing module, focused tests and operator documentation in meguri-pet. No new dependencies, Core API changes or production configuration/deployment changes are required. Existing unrelated working-tree edits are preserved and excluded from the local commit.
