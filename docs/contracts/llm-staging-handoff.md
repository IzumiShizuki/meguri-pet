# LLM Agent Staging handoff

## Provider location

Meguri core uses an authenticated OpenAI-compatible endpoint. The temporary
base-model route is:

```text
MEGURI_LLM_PROVIDER=openai-compatible
MEGURI_LLM_BASE_URL=https://api.deepseek.com/v1
MEGURI_LLM_MODEL=deepseek-chat
MEGURI_LLM_API_KEY_FILE=/opt/meguri/staging/secrets/llm-api-key.txt
```

No model weights, GPU runtime or training stack run on the server. A later
fine-tuned candidate remains behind the same provider interface.

## Release identity

```text
MEGURI_LLM_RELEASE_CHANNEL=candidate
MEGURI_LLM_BASE_MODEL_REVISION=deepseek-chat
MEGURI_LLM_ADAPTER_REVISION=none
MEGURI_LLM_ADAPTER_SHA256=none
MEGURI_MODEL_REGISTRY_ID=external-deepseek-chat-staging
```

External adapterless base models may omit Meguri adapter headers. A registered
adapter-backed model must supply base revision, adapter revision and adapter
SHA-256, and the gateway response headers must match. The Release Manifest
records `llm_base_model`, nullable `llm_adapter_revision`, nullable
`llm_adapter_sha256`, and `model_registry_id`.

## Health, readiness and rollback

`/health/live` proves process response. `/health/ready` requires Manifest,
artifact, provider, secret and database identities to match. It does not make
an upstream billable LLM request. Real Turn/SSE smoke is a separate acceptance
gate.

Candidate and last-good are switched by immutable release state; rollback does
not rebuild an image or model. Schema-invalid output, timeout, HTTP failure or
release-header drift fails closed. Do not return Mock text as a successful
managed response.

## Current gate

The environment currently contains an unavailable placeholder key solely to
exercise file-secret and readiness behavior. A synthetic request reached the
DeepSeek provider and failed with sanitized HTTP 401 evidence. Full Staging
remains NO-GO until a dedicated key is provisioned without printing it and the
following pass:

1. real Turn and strict JSON Schema validation;
2. SSE order, reconnect and cancellation;
3. timeout and upstream failure behavior;
4. Prompt + RAG integration;
5. native MemoryProvider integration with synthetic identities;
6. last-good rollback after candidate failure.

The later fine-tuned candidate must also carry its evaluated registry entry,
adapter digest and rollback model ID. Production must never fetch a local
training endpoint or floating adapter automatically.
