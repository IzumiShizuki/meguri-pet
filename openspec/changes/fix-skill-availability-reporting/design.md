## Context

Reuse VALID/INVALID/QUARANTINED reports, ENABLED/DISABLED bindings, frozen capabilities and existing SUCCESS/FAILED observations. The repository is an independent candidate based on a2db597; production is owned by the primary repair task.

## Decisions

- Retain immutable import reports in storage. Derive current dependency compatibility without rewalking packages; activation still verifies package integrity and license confirmation.
- A retained activeDigest does not imply an enabled binding. Public metadata operations explicitly do not perform installation.
- Freeze current dependency state with the turn; missing exposure is not automatically an approval request. The actual executor remains authoritative for approvals and invocation.
- Use only enumerated application-owned failure codes. Unknown exception messages and remote tool text never become failure summaries. Retain retry semantics and unknown external write outcomes.
- Keep structured tool results on the existing untrusted observation-to-chat path. Verify this path with synthetic transports/providers; do not use real private data.
- The MCP normalizer produces READ_TOOL + EXTERNAL + RISK_BASED; ReAct previously rejected it twice even with a valid approval. Permit only mcp.* READ_TOOL with matching mcp: owner scope, allowed network and non-NONE approval, and require operationId/approvalId. The executor still binds approval to capability/version/operation/snapshot/turn/identity/input. There is no automatic approval request/resume UI in this change. Without an idempotency key, existing approved MCP read retries remain at most two; this is not a single-use approval grant.
- Action validation rejections become bounded failed observations without executing or counting a tool call, so missing approval reaches final chat context.
- MCP outputSchema is optional in the 2025-11-25 Tools specification. Omission permits an untrusted result object; an explicit malformed schema or wrong declared output still fails. Source: https://modelcontextprotocol.io/specification/2025-11-25/server/tools#output-schema
- Eligible Skill metadata is ranked before blocked status entries within the same eight-candidate bound, avoiding starvation. Missing dependency reasons contain only bounded dependency names and generated explanations, never environment values.

## Risks / Trade-offs

Ready dependency metadata is not proof of remote endpoint success. Old frozen turns retain their original view; new turns re-evaluate. Dependency predicates observe names/presence only and do not disclose environment values. Actual network acceptance remains blocked by the previously documented public-address guard.
