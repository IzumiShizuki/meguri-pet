# Meguri companion workspace

This workspace contains both an offline Python mock harness and the current Java Core-backed desktop runtime. The Python harness remains useful for isolated contract and simulation work; the native desktop launcher starts Java Core, the local gateway, and optional local speech services. Runtime selection depends on launcher configuration and local resources, so the mock harness is not evidence that the desktop is running against mocks.

## Run the mock core

```powershell
D:\environment\anaconda3\envs\py314\python.exe -m uvicorn services.meguri_core.app:app --host 127.0.0.1 --port 8000
```

The service exposes `POST /v1/chat/respond`, asynchronous `POST /v1/turns`, turn status, cancellation, runtime state/override, and an in-memory memory inspection endpoint. `POST /v1/turns` accepts an optional `Idempotency-Key` header. SSE at `GET /v1/sessions/{session_id}/events` supports live delivery and reconnect replay through `after_sequence` or `Last-Event-ID`.

The canonical build id is read from `datasets/meguri/build_report.json`, validated against RAG and expression exports, and propagated through every event envelope.

The phase-1 memory layer now exposes a replaceable `MemoryProvider`, provider-independent companion policy, explicit review/export/delete APIs, and bounded short-term context isolated by `user_id + client_id + session_id`. `FakeMemoryProvider` is still the only enabled implementation; no production MemoryOS or PostgreSQL connection is made.

`MockLLMProvider` remains the offline default. `OpenAICompatibleLlmProvider` is available behind explicit environment configuration, uses the canonical system prompt and strict JSON Schema response format, bounds injected context, and fails closed on malformed model output. See `docs/llm-provider.md`.

`adapters/astrbot/astrbot_plugin_meguri_gateway` contains the loadable AstrBot gateway plugin. It hashes platform identifiers, separates private/group sessions, disables TTS and screen context, intercepts `/meguri` before AstrBot's default LLM, and only accepts a loopback Core URL by default. Its optional Relay client supports device listing and two-phase remote-task confirmation, but remote commands are disabled by default. Repository tests do not install it into the production `/opt/astrbot/data` directory.

The AIRI spike is split into `packages/protocol`, `adapters/airi`, `packages/renderer-contracts`, `apps/desktop-airi`, and `local-services/tts-adapter`. It uses Node 24's native erasable TypeScript support, so the protocol/reconnect/PNG/TTS tests run without installing AIRI or changing its upstream checkout. See `docs/airi-upstream-inventory.md` for the pinned read-only reference.

The browser-facing path is split into the shared `packages/client-sdk`, `adapters/website`, and `apps/website-client` demo. It injects a host-bound user identity, persists only session recovery metadata, reconnects SSE turns after interruption, exposes cancellation, and allows only loopback core URLs by default. The core CORS policy is restricted to local Vite preview/development origins in phase 1.

## Verify

```powershell
D:\environment\anaconda3\envs\py314\python.exe -m unittest discover -v
D:\environment\nodejs\runtime\node-v24.17.0-win-x64\node.exe --test tests-ts\protocol.test.ts tests-ts\renderer.test.ts tests-ts\airi-adapter.test.ts tests-ts\tts-adapter.test.ts tests-ts\website-adapter.test.ts
```

In the Python harness, the provider interfaces are intentionally replaceable and `MockLLMProvider`, `MockRagProvider`, and `FakeMemoryProvider` remain the local defaults. The Java desktop runtime has its own provider and persistence configuration, described below; these mock-harness checks do not validate a production deployment.

## Java Core desktop runtime

The first Java migration phase lives in `java/meguri-core`. It uses JDK 21,
Spring Boot WebFlux and LangChain4j, starts on port `18080`, and preserves the
Python memory bridge on port `8000` when that bridge is enabled. The Java
application defaults to the `local-mock` profile, loopback port `18080`, mock
LLM, and in-memory turn journal. The desktop Core launcher can override these
defaults: it enables execution mode and Limited ReAct, configures the
OpenAI-compatible LLM when its local key file exists, and
selects PostgreSQL turn storage when a dedicated runtime database file is
available. Memory bridge activation is configured separately. See
`docs/p2-runtime-map-2026-10-08.md` for the exact default/launcher split and
rollback controls, and `java/meguri-core/README.md` for Core details.

The native desktop launcher starts Core on `127.0.0.1:18080`, its loopback
gateway on `127.0.0.1:15173`, and local TTS on `127.0.0.1:9880` when those
services are not already listening. Local commitment follow-up, desktop
notifications and speech therefore require the relevant desktop process and
resources to be running; TimePrism reminders remain a separate server-backed
path.

The trusted local launcher also enables a bounded, loopback-only adapter for
controlling the Shizuki desktop app. See `docs/shizuki-desktop-control.md` for
startup instructions, supported natural-language operations, security
boundaries, and troubleshooting.

The native desktop can also initiate a short, context-grounded conversation
after an idle period using the existing initiative controls. Focus, local quiet
hours, AI consent, voice activity and daily limits gate this independent
assistant-only flow. Core and desktop must both include the reach-out endpoints;
older or unavailable services stay silent. See `docs/idle-companion-reach-outs.md`.

## Local text-model MVP

The `codex/mvp-auto-fit` branch carries a reproducible quick-fit path for the
Meguri text contract. It uses the existing `meguri-llm` environment, the pinned
Qwen3.5-4B revision and a derived read-only dataset; it never reads locked eval
content or changes the model registry.

```powershell
$env:HF_HUB_OFFLINE='1'
$env:UNSLOTH_COMPILE_DISABLE='1'
$env:UNSLOTH_COMPILE_LOCATION='D:\environment\cache\meguri-llm'
D:\environment\anaconda3\envs\meguri-llm\python.exe -m training.llm.scripts.run_mvp `
  --experiment-id meguri-qwen35-4b-mvp-20260718-v6
```

The checked local MVP artifact is an experimental LoRA adapter under
`training/llm/artifacts/checkpoints/` with its `mvp_manifest.json`; generated
model files stay ignored by Git and should be published through Git LFS or a
GitHub Release rather than committed into ordinary source history. See
`docs/mvp-release-audit.md` and the model card for the exact provenance and the
resume command for formal training.
