# ADR 004: Existing MemoryOS

Status: pending evaluation.

The running file-backed MemoryOS instance is treated as external production state. Do not modify, migrate, restart, or delete it until consumers and ownership are confirmed. Future tests must use an isolated scope and cover isolation, retrieval, update, delete, conflict, concurrency, restart recovery, failure, and authentication exposure.

