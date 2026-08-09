# TTFT comparison report

## Measurement status

- Status: `NOT_MEASURED | SYNTHETIC_ONLY | REAL_END_TO_END`
- Character TTFT metric: `client.first_render.at - turn.received_at`
- Platform-message metric (non-character clients): `first_platform_message_latency`
- Claim rule: report improvement only when both runs use the same workload, metric boundaries, provider/model route, execution/retrieval mode, and client type.

## Build and workload identity

| Field | Before | After |
| --- | --- | --- |
| release_id |  |  |
| git_commit |  |  |
| image_digest |  |  |
| response_contract_revision |  |  |
| prompt_revision |  |  |
| provider / model |  |  |
| execution_mode / retrieval_mode |  |  |
| client_type |  |  |
| workload revision |  |  |
| sample count / warmup |  |  |

## Comparable distributions

| Metric (ms) | Before P50 | Before P95 | Before P99 | After P50 | After P95 | After P99 | Status |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| pre_provider_latency |  |  |  |  |  |  | `NOT_MEASURED` |
| provider_queue_latency |  |  |  |  |  |  | `NOT_MEASURED` |
| provider_first_token_latency |  |  |  |  |  |  | `NOT_MEASURED` |
| event_persist_latency |  |  |  |  |  |  | `NOT_MEASURED` |
| sse_transport_latency |  |  |  |  |  |  | `NOT_MEASURED` |
| client_render_latency |  |  |  |  |  |  | `NOT_MEASURED` |
| end_to_end_ttft |  |  |  |  |  |  | `NOT_MEASURED` |
| first_platform_message_latency |  |  |  |  |  |  | `NOT_APPLICABLE` |

## Missing timestamp reasons

List each missing canonical timestamp and its stable reason code. Do not substitute zero unless the shared `performance.v1` fixture defines a skipped-stage zero duration.

## Boundary and exclusions

- Real Provider and provider queue included: `yes / no`
- PostgreSQL event commit included: `yes / no`
- HTTP/SSE encoding and socket delivery included: `yes / no`
- Reverse proxy included: `yes / no`
- Client receive and visible render included: `yes / no`
- Synthetic delays or fake services used:

## Quality, durability, and rollback checks

- Exact replayed text and terminal state:
- Persistence-before-broadcast invariant:
- Recall/persona/permission regression result:
- Feature flags and off-state result:
- Rollback command or procedure:

## Reproduction

```powershell
./scripts/benchmark_ttft.ps1 -Samples 100 -Warmup 15 -Label local-synthetic
```

The script above produces a synthetic in-process comparison only. Replace this section with the real deployment, traffic generator, client instrumentation, and report query commands before setting a production rollout threshold.
