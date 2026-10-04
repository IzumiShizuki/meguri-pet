## Why

On this Windows setup, Meguri Core fails during Java networking initialization when the JDK places its internal Unix-domain loopback socket under the default temporary directory. Core then never binds port 18080, and the desktop gateway returns 502. A short writable socket directory let the same launcher start Core and return HTTP 200, so that setting should be made part of the normal launch path.

## What Changes

- Prepare a short, writable repository-local directory for JDK Unix-domain sockets before invoking Maven.
- Pass that directory to Java while preserving any existing `JAVA_TOOL_OPTIONS` values.

## Capabilities

### New Capabilities
- `local-java-core-startup`: Reliable startup of the loopback Java Core through the local launcher.

### Modified Capabilities

## Impact

- `ops/scripts/start-meguri-core.ps1` and the Java process environment it creates.
- Local desktop gateway health checks that depend on Core listening on port 18080.
