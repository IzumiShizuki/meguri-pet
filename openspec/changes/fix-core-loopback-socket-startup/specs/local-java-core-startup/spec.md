## Purpose

Ensures the local launcher starts Meguri Core with a healthy loopback endpoint that the desktop gateway can reach.

## ADDED Requirements

### Requirement: Local Core launcher publishes a healthy service
The local Windows Core launcher MUST start the Java service so it listens on loopback port 18080 and `GET /health` returns HTTP 200 with status `ok`.

#### Scenario: Default JDK socket directory cannot establish its loopback socket
- **WHEN** the launcher starts Core in the configured Windows environment
- **THEN** it prepares a short writable socket directory for Java and Core exposes its health endpoint on port 18080.

#### Scenario: Java tool options already exist
- **WHEN** `JAVA_TOOL_OPTIONS` contains user-configured options
- **THEN** the launcher preserves those options while adding the socket directory required for Core startup.
