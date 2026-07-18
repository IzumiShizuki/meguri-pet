# Meguri MVP integration and release audit

This note records the integration boundary for the short-lived, automatic-fit
MVP branch. It is an audit artifact, not evidence of staging or production
readiness. The canonical dataset under `datasets/meguri` remains read-only.

## Verified local path

The repository's offline path is already usable end to end:

1. `services.meguri_core.app` starts with `MockLLMProvider`, `MockRagProvider`
   and `FakeMemoryProvider` when no provider/database environment is set.
2. `POST /v1/chat/respond` returns the committed `LlmResponse` contract,
   runtime state, expression mapping and canonical build ID.
3. `POST /v1/turns` plus the SSE session endpoint exercises asynchronous turns,
   replay and cancellation.
4. The AIRI, website and AstrBot packages are loopback/offline adapters. They
   are not installed into AIRI or AstrBot production directories.

Commands used for the local audit (from the repository root):

```powershell
D:\environment\anaconda3\envs\py314\python.exe -m unittest tests.test_llm_gateway tests.test_llm_checkpoint_selection tests.test_llm_training_utils tests.test_llm_staging_rollback tests.test_release_manifest -v
D:\environment\nodejs\runtime\node-v24.17.0-win-x64\node.exe --test tests-ts/protocol.test.ts tests-ts/renderer.test.ts tests-ts/airi-adapter.test.ts tests-ts/tts-adapter.test.ts tests-ts/website-adapter.test.ts
```

The audit run passed 16 Python tests and 20 TypeScript tests. A direct
`TestClient` probe also returned HTTP 200 for `/health`, `/health/live`,
`/health/ready` and `/v1/chat/respond` with build ID
`meguri_v2_02c3db0c507d7c2d`.

## MVP model boundary

The normal Qwen/Unsloth path is not a portable no-GPU demo: it requires CUDA,
the full L-001 probe, the 2,626/566 derived dataset gate, and a local adapter
artifact. The LLM gateway additionally requires a registry entry, a matching
adapter digest and a configured routing state. An empty registry/routing file
must remain not-ready.

Therefore an auto-fit MVP may be used only as a local demonstration provider
or explicitly marked experimental. It must not be labelled `staging_active`,
`production_active`, or a completed fine-tune. Do not bypass locked-eval or
fabricate registry/comparison evidence just to make the gateway report ready.

The MVP artifact should preserve enough provenance to resume formal training:

- immutable experiment ID and branch/commit;
- base model identifier and revision (or the exact MVP provider implementation);
- source build ID and derived dataset manifest/hash;
- prompt and response-schema hashes;
- training/fit configuration, seed, sample/step counts and environment;
- output artifact hash and a JSON-schema smoke transcript;
- an explicit `locked_eval_accessed: false` marker for this quick run.

The later full run can then replace the MVP artifact behind the same provider
contract without changing adapters or renderer code.

The concrete local artifact and resume boundary are recorded in
`docs/mvp-model-card.md`. The quick run used 100/20 rows and stopped at the
resumable step-25 checkpoint; its extracted adapter passed one strict local
JSON inference smoke. This is an experimental checkpoint result, not a frozen
quality evaluation.

## Minimum acceptance checklist

- [ ] A clean checkout of the MVP branch can run the local core command in
  `README.md` and answer one `POST /v1/chat/respond` request.
- [ ] The response validates as `LlmResponse` and carries the canonical build
  ID; expression resolution falls back safely to a real neutral sprite.
- [ ] One asynchronous turn reaches `turn.completed` through SSE; replay from
  `Last-Event-ID` (or `after_sequence`) does not duplicate text, and canceling
  an active turn reaches `turn.cancelled`.
- [ ] AIRI/website adapter tests pass; any manual demo uses a loopback URL.
- [ ] The MVP fit/provider smoke emits valid JSON for representative JP/ZH
  inputs and records its artifact/manifest hashes.
- [ ] The GitHub README labels this as a local MVP/demo and separately lists
  the formal-training follow-up. No hosted/staging success is claimed without
  authenticated provider and deployment evidence.

## GitHub and resume wording

Safe wording: "Built a local Meguri character-runtime MVP with a validated
JSON response contract, RAG/memory boundaries, SSE turn protocol, and AIRI /
website adapters; added a reproducible quick-fit model/provider path and
artifact provenance for later full fine-tuning."

Avoid claiming that the quick-fit artifact is production quality, that AIRI is
already a packaged desktop pet, or that staging/production was deployed. The
current repository evidence supports a local, headless integration demo only.

## Known risks

- The full Qwen3.5 configuration and `LocalUnslothBackend` are CUDA-bound and
  may be unavailable on a clean GitHub runner.
- On the installed `meguri-llm` environment (RTX 5060 Ti, torch 2.8 and
  Unsloth 2026.2.1), the tokenizer compatibility guard now accepts only the
  template whitespace after `<|im_end|>`, and the recorded v5 probe passes
  CUDA/BF16, assistant masking, forward/backward, adapter save/reload and
  minimum schema-shaped JSON inference. The runtime also uses
  `UNSLOTH_COMPILE_DISABLE=1` and a short compile cache to avoid a Windows BF16
  Conv1d mismatch. These are environment-specific compatibility settings, not
  a quality claim.
- The MVP adapter stopped at a safe step-25 checkpoint from a requested
  50-step smoke budget. It passed one strict local adapter inference, but no
  locked evaluation, comparison gate or staging registry update was run.
- The checked-in model registry and routing state are intentionally empty;
  gateway readiness is expected to be false until a genuinely evaluated
  adapter is registered.
- Core local defaults use in-memory fake memory and mock LLM responses; they do
  not prove native PostgreSQL/pgvector, remote provider, or production auth.
- A local adapter is not an AIRI or AstrBot installation and does not change
  existing production data or process state.
