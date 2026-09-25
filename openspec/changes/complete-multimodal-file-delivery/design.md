## Context

See `proposal.md` for motivation. The AIRI renderer already turns selected images into OpenAI-compatible `image_url` data, and the `@` picker already carries an Everything result as a local-file reference. The adapter currently strips both forms to metadata before the Java Core calls the model. The local gateway only routes resource searches to Java, while ordinary turns default to the hosted Core. The generated-artifact feed and default-application IPC exist but the bubble component is not mounted into the active stage.

## Goals / Non-Goals

**Goals:**

- Carry a user-approved image or PDF across the existing AIRI adapter and Core boundary as bounded multimodal content.
- Make model selection explicit and deterministic: current model when declared capable, otherwise configured `dsv4p`, otherwise an actionable failure.
- Retain the existing local-resource and artifact root safety boundaries.
- Present verified generated files in both streamed chat text and the desktop-pet scene.

**Non-Goals:**

- General document parsing, archive inspection, executable sharing, or arbitrary local filesystem access.
- Implicit provider feature probing or retrying a model after it may have partially processed a turn.
- Letting a prompt skill trigger Planner/Agent execution or granting it tool authority.

## Decisions

### Preserve the existing attachment wire shape and add only explicit content access

Dropped images continue to use the stage's existing data-URL image attachment flow. The adapter transforms only validated image data URLs into an inline multimodal attachment. `@` results carry a canonical local reference and an explicit `multimodal_read` access request; Java revalidates it immediately before reading. Images (PNG, JPEG, GIF, WebP) and PDFs are the supported initial set. Unsupported types fail visibly rather than silently becoming metadata. This avoids a new Electron filesystem bridge for drag/drop and keeps local content out of the model unless the user submits the turn.

### Use a shared Java safe-local-path policy

Everything search and multimodal file resolution use one policy for local-drive, canonical, non-symlink regular files and denied path segments/extensions. Search filtering alone is insufficient because files can change between selection and send. A shared policy removes that time-of-check/time-of-use inconsistency.

### Route selected desktop sessions to the local Core

The gateway validates a multimodal marker and request payload, then binds the desktop session to local Core routing. Subsequent session requests remain local so the server-side conversation state stays coherent. Non-multimodal sessions retain the existing hosted Core route and bearer-token boundary. The gateway has a strictly bounded larger body allowance only for a validated multimodal turn.

### Use a declared capability list and fallback configuration

`MEGURI_LLM_MULTIMODAL_PRIMARY_MODELS` declares which primary model IDs may receive content. `MEGURI_LLM_MULTIMODAL_FALLBACK_MODEL` defaults in the local template to `dsv4p`; optional fallback base URL/key settings override, otherwise the existing outer-model provider settings are reused. This makes fallback decisions testable and avoids speculative provider calls. If no declared route exists, Core produces a specific configuration error before model invocation.

### Convert only verified generated artifacts into UI links

Core extracts an explicit `[[meguri-artifact:output/...]]` or `[[meguri-artifact:reports/...]]` marker, plus an equivalent safe generated-root path, resolves it under `output` or `reports`, and emits a verified artifact event. The adapter turns that event into the existing blue Markdown/local-open link contract. The mounted bubble layer also polls the existing feed more frequently and places each bubble randomly within a bounded pet-scene region. Existing Electron artifact IPC remains the final validator and default-opener.

### Offer an opt-in prompt skill, but do not depend on it

An available `PROMPT_SKILL` describes the marker only when a capability plan elects to expose it. Runtime path extraction works independently so fast/normal turns do not acquire planner latency or require an agent. A prompt skill is sufficient for output etiquette; no filesystem skill is added because content resolution belongs behind the Core safety policy.

## Risks / Trade-offs

- [Image data expands request bodies] → Enforce per-file and aggregate byte limits, validate MIME/data-URL shape, and retain a narrow larger gateway limit for marked local turns only.
- [Provider capability settings drift] → Use a documented explicit allow-list, configuration errors that name the missing setting, and unit tests for selection.
- [A model tries to surface an unsafe path] → Resolve and validate artifact paths in Core and validate again in Electron before opening.
- [Polling causes a slight bubble delay] → Reduce the existing feed interval while also streaming a chat link immediately; no new renderer event protocol is required.
- [PDF support differs by compatible endpoint] → It is routed only to a declared capable primary or the designated fallback; endpoint rejection remains visible rather than converting it to text.

## Migration Plan

1. Add the feature behind empty-by-default capability settings; existing text-only turns are unchanged.
2. Add `dsv4p` as the local environment template fallback and reuse the outer API key if no override is supplied.
3. Build and test Java, gateway, adapter, and stage; restart only the local desktop stack.
4. Roll back by clearing the multimodal capability/fallback settings and redeploying the previous Java/AIRI artifacts. Existing text, Everything, and artifact-feed behavior continue to work.
