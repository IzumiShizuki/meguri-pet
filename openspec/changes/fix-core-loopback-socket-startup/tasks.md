## 1. Launcher Environment

- [x] 1.1 Create a short repository-local JDK Unix-domain socket directory before Maven starts.
- [x] 1.2 Add or replace the `jdk.net.unixdomain.tmpdir` value in `JAVA_TOOL_OPTIONS` while preserving unrelated options.

## 2. Runtime Validation

- [x] 2.1 Start Core through the normal launcher and confirm `/health` returns HTTP 200 with status `ok`.
- [x] 2.2 Confirm the desktop gateway `/core/health` proxy returns HTTP 200.
