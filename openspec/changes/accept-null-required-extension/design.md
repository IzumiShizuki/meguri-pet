## Context

See `proposal.md` for motivation. Core JSON serializes absent optional values such as event `required_extension` and snapshot turn `error` as `null`. In browsers, the SDK's SSE request also triggers a CORS preflight for `Meguri-Protocol-Version` and `Last-Event-ID`, but the loopback gateway does not currently allow those headers. The browser therefore blocks SSE, fallback snapshot parsing rejects `error: null`, and the renderer reports a connection failure even though the turn completed.

## Goals / Non-Goals

**Goals:**

- Normalize the nullable wire representation at the shared protocol parser boundary.
- Permit the SDK protocol-version header in trusted desktop CORS preflights.
- Keep all existing extension negotiation and validation behavior for present identifiers.
- Lock the behavior with parser-level and SSE-stream regression coverage.

**Non-Goals:**

- Changing Core event serialization or the HTTP/SSE contract.
- Relaxing validation for empty strings, numbers, objects, or unsupported required extensions.
- Adding desktop-only recovery logic around protocol errors.
- Broadening the trusted desktop origin or client identity rules.

## Decisions

### Normalize `null` at the protocol boundary

The parser will regard both `undefined` and `null` as absence, and return `required_extension` as `undefined` for either wire representation. This keeps downstream code aligned with the existing optional TypeScript property and prevents nullable wire details from leaking into every consumer.

Alternative considered: change only the Java Core serializer to omit null fields. That would leave released or independently deployed Core versions incompatible and would not make the client robust to a valid JSON representation already observed in production.

### Preserve strict validation for present values

When the value is neither `undefined` nor `null`, the existing non-empty string validation and supported-extension negotiation remain unchanged. This is intentionally narrower than coercing falsy or arbitrary values.

Alternative considered: ignore all invalid optional values. That would hide malformed required-extension declarations and could allow a client to process events whose semantics it does not support.

### Test both direct parsing and SSE framing

The regression test will exercise the event parser with `null`, omitted, valid-string, and invalid values, plus an SSE block containing the Core-compatible null field. The live gateway replay remains the end-to-end verification because it reproduces the original desktop failure at the SDK boundary.

### Apply the same nullable normalization to snapshot turn errors

The snapshot parser will map `error: null` to optional absence while continuing to reject non-string, non-null values. This keeps SSE fallback authoritative instead of allowing a serialization detail to turn successful completed turns into client failures.

### Allow the SDK SSE headers without widening trust

The gateway CORS response will add only `Meguri-Protocol-Version` and `Last-Event-ID` to its existing allowed-header list. Origin, Electron user-agent, desktop client, and session identity validation remain unchanged.

## Risks / Trade-offs

- [Risk] Normalizing to `undefined` changes the exact object shape for callers inspecting raw keys → The public type already declares an optional string, and normalized absence is the intended contract.
- [Risk] A broader nullable-field mismatch may exist elsewhere → Keep this patch scoped to the observed field and use future failures to extend compatibility deliberately.
- [Risk] CORS changes can accidentally widen browser access → Add only the exact SDK header and retain all existing origin and desktop identity gates.

## Migration Plan

1. Land the parser and regression tests.
2. Rebuild and restart the desktop client so the shared protocol code is bundled.
3. Replay the affected persisted desktop session and drive a browser renderer turn through the gateway.

Rollback is a single parser change; no persisted data or server migration is required.
