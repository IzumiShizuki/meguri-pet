## Context

See `proposal.md` for the observed startup failure. The launcher invokes Maven and inherits its Java options; the Core process uses the JDK networking stack before it can bind port 18080.

## Goals / Non-Goals

**Goals:**
- Give the JDK a short, writable directory for automatically bound Unix-domain sockets.
- Keep existing Java tool options intact and make the setting reproducible for normal launcher runs.

**Non-Goals:**
- Change Core endpoints, gateway routing, authentication, or provider configuration.
- Change machine-wide Java or Windows settings.

## Decisions

- Derive a dedicated socket directory from the script location under the repository's existing `tmp` directory, resolve it to an absolute path, and create it before Maven starts. The path remains short on this installation and does not rely on a user-specific profile directory.
- Set `jdk.net.unixdomain.tmpdir` through `JAVA_TOOL_OPTIONS`, because the Maven-launched JVM inherits it and this was the setting that allowed Core to start in the affected process environment. Preserve unrelated existing options and replace any previous value for this property so the launcher consistently uses its known writable directory.
- Keep the workaround scoped to this launcher's child process. A machine-wide JDK change would affect unrelated Java applications and is unnecessary.

## Risks / Trade-offs

- The repository-local `tmp` directory must be writable. The launcher creates the dedicated child directory before starting Maven, so an access failure is reported at startup rather than surfacing later as a gateway 502.
- `JAVA_TOOL_OPTIONS` is also read by Maven's JVM. The added option is specific to JDK Unix-domain socket placement and does not change application logic.

## Migration Plan

The launcher creates the directory automatically and passes the JDK property on each run. Rollback consists of removing that launcher setting; no persistent machine configuration or user data migration is involved.
