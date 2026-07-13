# LLM provider boundary

The core defaults to `MockLLMProvider` and performs no model network calls. A real endpoint is enabled only when `MEGURI_LLM_PROVIDER=openai-compatible` is set explicitly.

Required configuration:

```text
MEGURI_LLM_PROVIDER=openai-compatible
MEGURI_LLM_BASE_URL=https://provider.example/v1
MEGURI_LLM_MODEL=model-name
MEGURI_LLM_API_KEY=<read from the local environment>
MEGURI_LLM_TIMEOUT_SECONDS=30
```

Loopback HTTP endpoints are allowed for local model servers. Non-loopback endpoints require HTTPS and an API key. The key is sent only in the Authorization header and is never included in prompts, errors, health output or repository files.

The adapter reads `configs/meguri_system_prompt.txt` and `configs/meguri_response.schema.json`, sends the latter as a strict JSON Schema response format, and validates the returned object again with Pydantic. Extra fields, Markdown-wrapped JSON, invalid enums and malformed provider envelopes fail the turn explicitly. The runtime does not silently replace a failed real-model response with mock character text.

Context is serialized as one JSON user message with bounded `runtime_state`, `user_message`, `canon_examples`, `long_term_memories` and `recent_context` fields. This keeps retrieved material and user text in data fields rather than promoting them to instruction roles.
