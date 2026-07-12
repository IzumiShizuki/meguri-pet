# ADR 008: Production safety gate

Status: accepted.

Phase 1 permits local development and server read-only verification only. Production writes require explicit approval after dependency mapping, backup and tested restore steps, rollback, port/network plan, health checks, logging, and change isolation are ready. Existing AstrBot, PostgreSQL, MemoryOS, OpenResty, firewall, certificates, Docker daemon, and volumes are out of scope for mutation.

