# ADR 003: Memory provider boundary

Status: accepted.

All business code depends on a replaceable `MemoryProvider`. Phase 1 uses `FakeMemoryProvider`; the existing MemoryOS adapter is evaluated before native pgvector or Mem0 work. Companion memory policy remains above provider-specific storage.

