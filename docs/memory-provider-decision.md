# Memory provider decision

Status: first-stage decision, 2026-07-13.

1. Local development uses `FakeMemoryProvider` behind the complete asynchronous provider contract.
2. Companion policy remains provider-independent and owns candidate review, credential rejection, deduplication, confidence/sensitivity gates and importance mapping.
3. Existing MemoryOS is retained untouched and represented by a shadow adapter only. Its missing stable IDs, supersede and delete operations disqualify it as the authority.
4. Mem0 is not deployed during this stage.
5. Native PostgreSQL/pgvector remains a design candidate, but no extension installation, migration or production database change is authorized until backup and restore gates pass.

The application must continue operating when memory search or write fails. Formal memories are shared by bound `user_id`; recent context remains isolated by `user_id + client_id + session_id`.
