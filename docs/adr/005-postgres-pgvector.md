# ADR 005: PostgreSQL and pgvector

Status: accepted for authoritative long-term memory.

Meguri uses a dedicated, environment-isolated PostgreSQL database with pgvector as the only authoritative long-term memory store. The existing shared PostgreSQL 16.13 instance remains out of scope and must not be modified by these migrations.

The baseline uses exact vector search plus tenant/user/status/current-version filters. HNSW remains deferred until a live exact-versus-ANN benchmark demonstrates a justified scale threshold without unacceptable recall loss. MemoryOS and Mem0 remain read-only import or shadow systems and cannot own formal IDs, versions, deletion or audit state.
