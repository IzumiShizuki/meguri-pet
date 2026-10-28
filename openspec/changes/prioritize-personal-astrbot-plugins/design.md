## Context

See proposal.md for motivation. Read-only audit on 2026-10-05 found 77 plugin directories and 489 decorated handlers. The live gateway routes all private and group messages and has only wife-plugin aliases in its release list. AstrBot removes the `/` wake prefix, evaluates handler filters, and records `activated_handlers` after platform/permission/session checks before dispatching the gateway. Command groups use a separate filter class. General listeners such as media parsing, image search and meme generation test ownership inside the handler, so a generic ALL-message match does not prove ownership.

## Goals / Non-Goals

**Goals:** Use the host's actual per-event dispatch decision, keep ordinary chat responsive, and support explicit plugin commands plus known automatic triggers without executing plugins twice.

**Non-Goals:** Enable disabled plugins, bypass group-only/platform/administrator restrictions, replace plugin data, integrate AstrBot LLM tools into Core, or change the desktop entrance.

## Decisions

1. Prefer `activated_handlers` to registry re-discovery. Only command/command-group/regex filters establish generic ownership. This preserves the host's full filtering and avoids mutating command parameter parsing by evaluating filters twice. If this metadata is unavailable, retain best-effort cached command discovery, including command groups, plus configured aliases.
2. Recognize known general listeners through a small module that inspects their existing instances/configuration. Bilibili share cards use the same structured metadata fields as the plugin. Media parser ownership uses its pure `_should_parse` and `extract_all_links` methods after its whitelist check. Image search respects its configured prefixes and pending-state timeout. Meme ownership reads the plugin's already loaded keywords/prefix and enablement, without loading resources or requesting the network in the gateway.
3. Suppress default AstrBot LLM on owned messages and return without stopping dispatch. Explicit `/meguri` remains authoritative; reconstruct the canonical prefix after AstrBot normalization before Core command parsing. Passive ALL-message listeners do not claim every conversation.
4. Keep manual aliases bounded by whitespace and accept slash-prefixed forms. Preserve configured aliases as a fallback for plugins whose filters cannot express ownership.
5. Catalogue observed installed source separately from live enabled status and actual API/network validation. Include full registered commands, aliases, group subcommands, triggers and operational limits; omit secrets/configuration values.

## Risks / Trade-offs

- Host internal metadata changes → defensive access and existing configured alias/registry fallback; tests use the installed framework's contract.
- Known automatic trigger implementations change → keep adapters narrow, skip failed inspection, and document unsupported LLM-only/background functions.
- Suppressing LLM on a stale manual alias can yield no response → use only audited aliases and let the owning plugin expose its own error.
- Live repository/server drift → compare affected file hashes first and retain the exact prior files/config for rollback.

## Migration Plan

Run gateway regressions and package checks. Produce the command catalogue and standalone archive. For the selected AstrBot entrance, apply only reviewed gateway files/configuration with a timestamped backup, reload only the gateway if supported, and verify plugin loading and synthetic routing without sending platform messages. Retain explicit rollback paths. No Core or middleware restart is required.
