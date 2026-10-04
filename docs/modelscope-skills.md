# ModelScope external Skills

Meguri can browse and import public ModelScope Skills through an opt-in source plugin. Imported instructions always remain `UNTRUSTED_EXTERNAL`: they cannot select AGENT mode, add scopes, read secrets, install dependencies, execute package scripts, or bypass the frozen Capability/approval/idempotency gates.

## Enable locally

The source is disabled by default. Run the existing Python Core bridge on loopback with a non-empty internal token, then start Java Core with the same token and the source flag:

```powershell
& 'D:\environment\anaconda3\envs\py314\python.exe' -m pip install -e .
$env:MEGURI_INTERNAL_BRIDGE_TOKEN_FILE = 'D:\environment\secrets\meguri\internal-bridge-token.txt'
$env:MEGURI_MODELSCOPE_SKILLS_ENABLED = 'true'
& 'D:\environment\anaconda3\envs\py314\python.exe' -m uvicorn services.meguri_core.app:app --host 127.0.0.1 --port 8000
```

Java Core fails startup when the source is explicitly enabled without a non-empty bridge token. Keeping the feature flag false does not require the bridge or its token, and installed local revisions remain available.

Use a separate terminal for `ops/scripts/start-meguri-core.ps1` and launch the native desktop with `ops/scripts/start-meguri-desktop.ps1`. Both explicit local launchers grant the local Core identity `skill:read` and `skill:manage`, so either startup order works. Hosted deployments must grant those scopes explicitly.

`modelscope-hub>=0.2,<0.3` is the only download dependency. The bridge pins `https://modelscope.cn`, passes an explicit empty SDK token, and therefore does not inherit environment or persisted ModelScope credentials. Meguri does not install `ms-agent`, execute `curl | bash`, support private Skills/collections, publish Skills, auto-install dependencies, or deploy MCP servers.

The bridge runs the synchronous SDK download outside the FastAPI event loop and defaults to a 12-second bounded wait (`MEGURI_MODELSCOPE_DOWNLOAD_TIMEOUT_SECONDS`). A timed-out request never returns staging metadata; cleanup runs after the SDK worker has actually stopped writing.

## Lifecycle

1. Search and detail requests read the public `https://modelscope.cn` OpenAPI.
2. Import downloads into a unique bridge staging directory.
3. Java records a disabled `QUARANTINED` transition, validates the package, and records `VALID` or `INVALID`.
4. A valid, compatible revision remains disabled until the user activates its SHA-256 digest. A missing license requires an explicit local-use confirmation and prohibits redistribution with Meguri.
5. Updates create a new disabled revision. Existing Turns and the active binding keep their prior digest until explicit activation.

The immutable package store defaults to `%USERPROFILE%\.meguri\skills\packages`. PostgreSQL deployments use `MEGURI_SKILL_STORE_MODE=postgres`; the local profile uses memory. Audit events include source, external ID, digest, actor, state transition, timestamp, and failure code, never Skill bodies or secret values.

## Runtime limits

Only server-authorized AGENT Turns can freeze external candidates. Each Turn freezes at most eight candidate digests and can view at most three Skill roots, six reference files, and 4096 estimated tokens through `meguri.skill.search` and `meguri.skill.view`. Returned text is labeled `USER_DATA/EXTERNAL_SKILL` and is never executable.

## Rollback and failure isolation

- Disable browsing/import/update immediately with `MEGURI_MODELSCOPE_SKILLS_ENABLED=false` and restart Core. Installed revisions continue to work.
- Disable an individual Skill from the desktop to stop selection for new Turns.
- Roll back by activating a previously validated digest in the revision ledger. Running Turns retain their frozen digest.
- Do not delete immutable package directories or audit rows as a rollback step.
- If ModelScope or the Python bridge is unavailable, only browse/import/update fail; local catalog reads, disablement, historical activation, and already frozen Turns remain available.

The optional public acceptance test is deliberately offline by default. Run it only when networking and the bridge are intentionally enabled:

```powershell
$env:MEGURI_MODELSCOPE_NETWORK_E2E = 'true'
$env:MEGURI_INTERNAL_BRIDGE_TOKEN = '<local-ephemeral-token>'
& 'D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd' `
  -f .\java\meguri-core\pom.xml '-Dtest=ModelScopeNetworkAcceptanceTest' test
```
