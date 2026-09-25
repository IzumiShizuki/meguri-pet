## Context

The repository already has a root README, module-specific READMEs, operational runbooks, and OpenSpec artifacts, but those documents answer different questions and are spread across the Python, Java, TypeScript, adapter, and operations trees. The new guide must remain a small, stable navigation layer rather than becoming a second source of truth for implementation details.

## Goals / Non-Goals

**Goals:**

- Make `.agent/README.md` the single first-read entrypoint for a new coding agent.
- Separate stable architecture and safety rules from command recipes that may evolve.
- Point to authoritative files instead of copying large contracts or configuration values.
- Use Windows paths and the repository's existing Python, Node, Maven, and JDK installations.
- Make verification and pre-merge expectations explicit, including the offline/mock defaults.

**Non-Goals:**

- No runtime, API, dependency, schema, deployment, or generated-artifact changes.
- No secrets, credentials, private endpoints, or machine-specific mutable state in the guide.
- No attempt to replace module READMEs, OpenSpec artifacts, or operational runbooks.

## Decisions

1. **Use four focused Markdown files.** `README.md` gives the reading order; `architecture.md` maps ownership and authority; `workflows.md` collects commands; `guardrails.md` records safety and change-management rules. This keeps each file small enough for an agent to load selectively.

2. **Link to source-of-truth documents.** The guide will reference `README.md`, `java/meguri-core/README.md`, `ops/README.md`, module READMEs, contracts, and OpenSpec changes. It will summarize only the decisions needed to choose a safe next action.

3. **Document defaults before opt-ins.** Local mock/offline behavior, loopback boundaries, explicit provider configuration, and secret-file conventions are the first-run path. Remote providers, PostgreSQL, scheduled jobs, and deployment actions are clearly marked as opt-in and higher risk.

4. **Keep commands reproducible on this workstation.** Recipes use the canonical `D:\environment` toolchain paths supplied by the repository instructions and avoid inventing installation steps. Commands that can mutate external state are labeled and separated from read-only checks.

5. **Treat the guide as a review aid, not a status snapshot.** Current branch names, temporary benchmark numbers, generated reports, and unfinished task counts stay out of `.agent/`; agents must inspect Git and OpenSpec status at the start of each task.

## Risks / Trade-offs

- [Documentation drift] → Keep links to authoritative files, include a “re-check status” rule, and verify referenced paths plus commands during implementation.
- [Overloading the first read] → Keep the entrypoint concise and direct deeper questions to focused files.
- [Accidental remote or destructive action] → Label deployment, database, scheduled-task, and credential operations as explicit opt-ins and preserve the existing safety boundaries.

## Migration Plan

Add the `.agent/` files, run link/path and repository validation checks, then commit them with the existing branch work. No rollback migration is needed; removing the documentation directory restores the previous behavior without affecting runtime state.
