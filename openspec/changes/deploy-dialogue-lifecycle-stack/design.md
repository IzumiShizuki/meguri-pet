## Context

The user now explicitly authorizes deployment after source-only verification. Read-only preflight shows local Core 18080 with Bing enabled and remote Core 18080 with web disabled; both lack input-check health metadata. The remote active container is meguri-staging-core-1, AstrBot uses host networking and the existing loopback Core. Prior validated artifacts and reports are the baseline; unrelated dirty work must be preserved.

## Goals / Non-Goals

Goals: activate actual behavior, identify artifacts by hash, retain rollback assets, and correct defects observed during acceptance.

Non-goals: sending real QQ test messages, enabling unconfigured Todo writers, changing database ownership or migrating formal user data, replacing unrelated services, or deleting historical deployment backups.

## Decisions

1. Use the existing verified SSH/Docker control path and existing runtime image layers; upload only the built JAR/configs and standalone plugin. Keep secret files on their current host and never print environment secrets.
2. Start a loopback candidate on an available port with compatible mount/network settings. Verify health flags and synthetic HTTP/SSE conversations before replacing the active port. Keep the previous image/container configuration for rollback.
3. Back up the exact AstrBot plugin directory and preserve its configuration/checkpoints. Upgrade the plugin via its supported reload/restart path; do not modify QQ routing targets or auto-send user-visible test messages.
4. Replace only verified local Meguri Core/gateway/desktop processes after recording their owners. Existing helper scripts and dedicated runtime/memory secrets remain authoritative. Health and build identity must both be checked after restart.
5. After deployment, fix only reproduced lifecycle/capability configuration defects, add focused regression, rebuild and revalidate. Previously completed suites need not be repeated unless new changes justify it.
6. Browser acceptance found that ChatDock independently restores an unsuccessful send's text and attachments after the store resets. Capture the composing session before preparation and reject stale continuations both before dispatch and after send completion. Keep retry restoration within the original session. Validate the built renderer against the real gateway/Core in an isolated browser with only native host APIs replaced by the existing development preview bridge; do not mock the backend.
7. Local acceptance returned search candidates but no extracted bodies. Bound each page to one second and reserve 150 ms before the lane deadline, including deferred extractor construction/DNS. Prefer extracted bodies when any are available. If none are available, retain only URL/age-policy-approved, bounded search snippets, label their content as unread page summaries, and expose a fixed degradation message. Never transform snippets into verified page bodies or latest-version claims.

## Risks / Trade-offs

- A service restart briefly interrupts active requests → check active turns and preserve checkpoint/config backups; retain an explicit rollback target.
- Healthy old services can be silently reused → verify input-check health state and artifact hash, not status alone.
- Remote disabled web configuration persists across image swaps → set and validate provider/enabled flags explicitly.
- Fresh source may depend on additional resources → candidate startup and conversation acceptance run before cutover.

## Migration Plan

Inventory and backup; stage candidates; validate; cut over Core and plugin; activate local stack; run synthetic acceptance; diagnose/fix/redeploy as required; record exact versions, checks and rollback paths.
