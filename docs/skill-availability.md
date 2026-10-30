# Skill readiness and public tool outcomes

Import, enablement, dependency presence, frozen exposure, invocation approval and execution are distinct facts. This change derives current readiness using existing validation reports, bindings and frozen capability checks; it adds no persistent state machine or automatic authorization.

| Evidence | Meaning |
| --- | --- |
| `DISCOVERED_METADATA`, `installation_performed=false` | The source returned public metadata. Each item separately reports whether its source/external ID is already in the local catalog; callability is not assessed. |
| `DISABLED` binding | Imported/selected revision is inactive, even if active_digest retains the last selection. |
| `QUARANTINED` / `INVALID` | Package validation is incomplete or failed; it cannot activate. |
| current `report.compatible=false` | Current required tools/environment names are missing or unsupported. Stored import validation evidence is retained unchanged. |
| `MISSING_DEPENDENCIES` | Enabled Skill metadata is relevant but a current dependency is absent; bounded generated reasons identify names, never environment values. |
| `NOT_EXPOSED_THIS_TURN` | Dependencies exist globally but are not present in this frozen capability set. This alone does not mean approval is pending: scope, network, mode or exposure policy can cause it. |
| `DEPENDENCIES_EXPOSED` | Prerequisite tool IDs are exposed in this turn. This is not invocation approval or proof of successful network access. |
| `MCP_READ_APPROVAL_REQUIRED` / `APPROVAL_REQUIRED` | The proposed call lacks the necessary approval, or its approval is pending/invalid for this exact proposal. No tool is invoked. Declined/cancelled approvals retain their own codes. |
| successful observation | The tool actually returned data accepted by its applicable schema. The bounded observation remains untrusted and feeds the final chat provider. |
| failed observation | Safe application-owned reason and retryability reach chat; no unrelated result substitutes for failure. |

## Implementation boundaries

- Current dependency checks use the same validator aliases, unsupported-tool and environment-presence rules as import. List/get return a fresh compatibility projection. Activation uses it and still verifies immutable package digest and missing-license confirmation. No stored import report is rewritten, and no package is rescanned on every turn.
- Production selection reads the current management projection and freezes it with the actual exposed tool IDs. Existing frozen turns do not change when dependencies change later. Eligible candidates sort before blocked metadata within the original eight-item bound; blocked instructions are neither proposed as planner Skill candidates nor disclosed by view.
- A disabled revision's API `active` flag is false while its selected digest remains available for explicit reactivation. Enablement in a management UI remains a binding fact, not a claim of endpoint callability.
- Skill instructions remain text (`executable=false`). Discovery/import do not grant tool scope, network permission, activation or approval.
- Known Skill/ModelScope/web_fetch/MCP failures use a finite allowlist of codes plus bounded numeric web HTTP statuses. Unknown exceptions retain generic failure codes; raw messages, remote error bodies and environment values are not copied to observations. MCP endpoint rejection, DNS failure, transport failure, valid remote RPC errors and malformed responses are distinct.

## Approved MCP reads in ReAct

The existing MCP normalizer marks read-hinted tools `READ_TOOL + EXTERNAL + RISK_BASED`. Previously ReAct rejected both external side effects and non-write approval requirements, even after approval. The exception is now limited to `mcp.*` READ_TOOL descriptors with an `mcp:` owner, that owner in required scopes, network allowed, and a non-NONE approval requirement. An action must supply operation_id and approval_id. The executor still checks exact capability/version, operation, frozen snapshot/turn, identity, idempotency key and input digest against the genuine approval.

No UI for automatically requesting, waiting for, or resuming MCP approval is added. An approval string is not itself authorization. Without an idempotency key the existing approved read retry policy permits at most two attempts; approval is not a single-use grant. With an idempotency key, existing tracked-external-effect deduplication, single attempt and UNKNOWN_OUTCOME rules remain authoritative. Do not automatically retry unknown outcomes.

The optional MCP outputSchema must not be interpreted as an empty-output schema when absent. Missing outputSchema permits a result object through existing untrusted normalization; explicit null/malformed schemas and invalid declared outputs still fail. This follows the [MCP 2025-11-25 Tools specification](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#output-schema). Explicit tool schemas and network/credential policy were not loosened.

## Acceptance plan

1. In an isolated synthetic package catalog, import a web_fetch-dependent package with no provider registered. It must stay disabled and report the missing tool. Register a synthetic provider, read status again, explicitly activate and verify the immutable import report is unchanged.
2. Remove that synthetic provider. A new turn reports missing dependencies; a previously frozen turn retains its original snapshot. With the provider globally present but not exposed in a turn, report NOT_EXPOSED_THIS_TURN rather than pending approval.
3. Disable and re-enable the same digest. API active becomes false then true; no implicit installation/activation occurs during discovery. Confirm nine relevant entries including eight blocked entries still retain the eligible one in the eight-item candidate budget.
4. Feed controlled public source failures and DNS policy rejection. Confirm distinct safe reasons, retryability, zero HTTP sends for rejected addresses and recovery in a later independent call. Never access the rejected address to test the guard.
5. Propose a synthetic MCP read without approval, with pending/declined/cancelled/forged approval and altered approved input. All must execute zero calls. A genuinely matching ACCEPT may return structured data as USER_DATA/UNTRUSTED into the final chat provider. Preserve existing write/local-control tests.
6. Keep production activation separate. Do not treat offline fixture success, imported package metadata, or historical DDG public-method success as live third-party Skill acceptance.

No primary checkout, running Core, production configuration, network policy, credential, third-party installation, Todo or Bilibili integration is changed by this candidate. Live ModelScope/DDG/MCP and real-model behavior remain unverified in this revision; the prior blocked public web_fetch probe was not retried.
