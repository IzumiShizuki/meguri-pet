# Meguri local framework bootstrap

This repository now contains the phase 0/1 local runtime skeleton described by the Notion contracts. It is deliberately offline-first: no API key, production server, AstrBot data directory, PostgreSQL, Redis, Kafka, or MemoryOS instance is contacted.

## Run the mock core

```powershell
D:\environment\anaconda3\envs\py314\python.exe -m uvicorn services.meguri_core.app:app --host 127.0.0.1 --port 8000
```

The service exposes `POST /v1/chat/respond`, asynchronous `POST /v1/turns`, turn status, cancellation, runtime state/override, and an in-memory memory inspection endpoint. `POST /v1/turns` accepts an optional `Idempotency-Key` header. SSE at `GET /v1/sessions/{session_id}/events` supports live delivery and reconnect replay through `after_sequence` or `Last-Event-ID`.

The canonical build id is read from `datasets/meguri/build_report.json`, validated against RAG and expression exports, and propagated through every event envelope.

The phase-1 memory layer now exposes a replaceable `MemoryProvider`, provider-independent companion policy, explicit review/export/delete APIs, and bounded short-term context isolated by `user_id + client_id + session_id`. `FakeMemoryProvider` is still the only enabled implementation; no production MemoryOS or PostgreSQL connection is made.

`adapters/astrbot/astrbot_plugin_meguri_gateway` contains the offline AstrBot gateway skeleton. It hashes platform identifiers, separates private/group sessions, disables TTS and screen context, supports `/meguri` runtime commands, and only accepts a loopback core URL by default. It is not installed into the production `/opt/astrbot/data` directory.

The AIRI spike is split into `packages/protocol`, `adapters/airi`, `packages/renderer-contracts`, `apps/desktop-airi`, and `local-services/tts-adapter`. It uses Node 24's native erasable TypeScript support, so the protocol/reconnect/PNG/TTS tests run without installing AIRI or changing its upstream checkout. See `docs/airi-upstream-inventory.md` for the pinned read-only reference.

## Verify

```powershell
D:\environment\anaconda3\envs\py314\python.exe -m unittest discover -v
D:\environment\nodejs\runtime\node-v24.17.0-win-x64\node.exe --test tests-ts\protocol.test.ts tests-ts\renderer.test.ts tests-ts\airi-adapter.test.ts tests-ts\tts-adapter.test.ts
```

The provider interfaces are intentionally replaceable. `MockLLMProvider`, `MockRagProvider`, and `FakeMemoryProvider` are the only phase-1 implementations; production MemoryOS, pgvector, Mem0, AstrBot, OpenResty, and AIRI adapters remain separate follow-up stages.
