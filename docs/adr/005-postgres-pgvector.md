# ADR 005: PostgreSQL and pgvector

Status: deferred.

The existing PostgreSQL 16.13 instance cannot be assumed pgvector-ready. Compare reuse after backup and restore testing against a dedicated Meguri database/container and a phase without pgvector. No migration or extension installation is authorized in phase 1.

