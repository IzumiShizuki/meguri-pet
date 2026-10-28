## Context

The deployed owner Todo writer already authenticates through CoreIdentityVerifier.verifyPrivilegedScope while normal chat uses auth-required=false. Reminder upload/feed still reject that policy before authentication. AstrBot has one verified QQ C2C creation operator, an exact private origin in protected deployment evidence, and reminders disabled. The event producer is the logged-in desktop, not the website or a server scheduler.

## Goals / Non-Goals

**Goals:** Restore the existing reminder route, configure the current verified recipient, and explain future account-oriented behavior concretely.

**Non-Goals:** Ship multi-account account management, extend shared editing permissions, install a WeChat adapter, add server scheduling, or migrate website data in this change.

## Decisions

1. Reuse privileged scope verification for both reminder routes. Specify the complete feed and upload session rather than relying on a global filter. Enabling global authentication would affect existing memory and conversation callbacks and is unnecessary.
2. Reuse the exact verified private origin only after matching the active owner, operator and enabled adapter. Preserve a full private backup and change only reminder target and switch plus normal default fields if absent.
3. Deploy a candidate based on the current deployed Core JAR, replacing only the tested controller class. Validate that every other JAR entry is identical, rather than deploying unrelated working-tree changes. Preserve existing mounts, environment, networks and chat policy; retain the old container for rollback. Apply only the settings helper/import/init hook and generated schema grouping to the deployed plugin; do not deploy unrelated persona-routing work.
4. Do not synthesize production reminder events or test Todos. Probe real feed access and use isolated collectors for synthetic event delivery. Check backlog before activation so no unexpected stale messages are delivered.
5. Describe the future model in a user-facing document: website account owns Todos; verified platform bindings carry independent edit and receive permissions; task-level recipients override account defaults; revocation stops later edits and sends. Individual accounts remain isolated. Different people collaborating should eventually be represented by membership rather than password sharing.
6. Use AstrBot's object configuration with collapsed=true for advanced settings. Retain legacy flat schema fields as invisible compatibility inputs, migrate their effective values once, persist a migration marker, and thereafter read grouped values first. Public switches and recipient/permission controls remain visible. Avoid deleting legacy data during AstrBot schema normalization.

## Risks / Trade-offs

- [No desktop process] → Explicitly document that current QQ production needs a logged-in running desktop; a durable server scheduler belongs to the future model.
- [Transport success is not human receipt] → Report that no real-message acceptance was performed.
- [Global legacy chat mode] → Privileged authentication tests cover wrong/missing credentials, tenant, client and session in that mode.
- [Existing unrelated work] → Patch the deployed JAR entry and preserve plugin source/config fields.

## Migration Plan

Create protected deployment/config backups, verify candidate feed access and ordinary health, activate only the controller patch, configure the verified recipient and restart AstrBot to load configuration. On failure restore the saved plugin configuration and prior Core container. Retain reminder cursor and inbox data throughout.
