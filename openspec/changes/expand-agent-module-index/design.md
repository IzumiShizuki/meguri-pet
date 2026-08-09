## Context

The repository has a Python FastAPI Core, a Java 21 WebFlux runtime, shared TypeScript contracts and clients, platform adapters, a desktop proxy/overlay, local services, training assets, and operational scripts. The existing `.agent/architecture.md` describes the broad split and one request path, but it does not expose enough entrypoints for a new agent to jump from a feature area to its source files and tests.

## Goals / Non-Goals

**Goals:**

- Add a package-oriented module index with stable entrypoints, responsibilities, API surfaces, and verification files.
- Show the main request, event/replay, startup, and authority flows using Mermaid diagrams that remain readable in Markdown viewers.
- Prefer representative source files and links to authoritative READMEs over copying every implementation detail.
- Make the distinction between Python and Java runtime paths, shared contracts, adapters, and external-state operations explicit.

**Non-Goals:**

- Do not generate a complete class dependency graph or claim that every private helper is documented.
- Do not change source code, routes, contracts, package manifests, ports, or operational behavior.
- Do not include credentials, private endpoint values, generated reports, current branch state, or benchmark claims.

## Decisions

1. **Create one focused `module-index.md`.** Group files by runtime and responsibility: Python Core, Java Core packages, TypeScript packages/adapters, desktop/local services, contracts/docs, and operations/training. Each row names a source entrypoint and a verification surface so an agent can continue navigation.

2. **Keep diagrams in `architecture.md`.** The architecture document is already the conceptual entrypoint. Add separate Mermaid diagrams for the canonical turn path, durable event/replay path, local startup dependencies, and authority boundaries instead of putting large diagrams in the index table.

3. **Use source links, not copied implementation.** Links target route declarations, package entrypoints, READMEs, tests, and OpenSpec/contract files. Descriptions are intentionally short and should be rechecked against code when behavior changes.

4. **Add a task-oriented reading matrix.** Map common tasks such as “change an API field”, “change memory”, “change provider streaming”, “change desktop rendering”, “add an adapter command”, and “deploy” to the first files and tests to inspect.

## Risks / Trade-offs

- [Index drift] → Include source-of-truth links and a verification checklist; avoid hard-coded counts and transient status.
- [Diagram oversimplification] → Label diagrams as canonical paths, keep authority boundaries explicit, and link to the implementation files for branch-specific behavior.
- [Too much documentation] → Group related packages and list representative files rather than every class.

## Migration Plan

Add the module index, update the architecture and entrypoint links, run path/link checks plus OpenSpec validation, and commit the documentation as a follow-up change. No runtime migration or rollback is required.
