# Meguri LLM staging handoff

This document is the human-readable handoff contract for promoting a trained
Meguri text adapter into the staging gateway path described by Notion pages 15
and 17. It authorizes `staging_candidate` routing only after the full evidence
package is attached. It does not authorize `production_candidate`,
`production_active`, or any production traffic change.

## Required identity bundle

Every handoff package must bind one immutable candidate to one explicit
rollback target:

- `model_registry_id`
- `rollback_model_id`
- `llm_base_model`
- `base_revision`
- `tokenizer_revision`
- `llm_adapter_revision`
- `llm_adapter_sha256`
- `generation_profile`
- `generation_profile_id`
- `generation_profile_sha256`
- `locked_eval_suite_id`
- `locked_eval_manifest_sha256`
- `llm_generation_profile_id`
- `llm_generation_profile_sha256`
- `llm_locked_eval_suite_id`
- `llm_locked_eval_source_build_id`
- `llm_locked_eval_manifest_sha256`
- `llm_independent_suite_validation_sha256`
- `prompt_sha256`
- `response_schema_sha256`
- `data_build_id`
- `training_config`
- `experiment_manifest`
- `validation_selection`
- `locked_eval_report`
- `comparison_report`

The candidate must already be registered in
`training/llm/registry/model_registry.json` with status `staging_candidate`.
The rollback target must be an explicit last-good model ID, not a placeholder.

## Required evidence

Attach evidence for each item below before any staging promotion claim:

1. Passing full `L-001` probe report for the exact base model revision and
   tokenizer revision, including the embedded `python -m pip freeze`
   environment lock.
2. Passing derived dataset manifest and `quality_report.json` for the approved
   `data_build_id`.
3. Passing validation checkpoint selection report.
4. Passing Base, Prompt+RAG, and exported-adapter locked evaluation reports on
   the same independently frozen 184-case suite, with one committed manifest
   identity and identical input hashes. A validation-selected profile must not
   reuse its excluded previous suite.
   The v2 manifest must also prove a new source build, zero sample/input/case/
   scene or normalized near-input overlap with train/validation and the
   previous suite, distinct preparer/approver identities, and measurement-only
   declarations.
5. Passing comparison report whose `staging_gate.status` is `pass` and whose
   `production_ready` field remains `false`.
6. Passing human review artifact with persona approval score at or above
   `0.90`, JP and ZH naturalness rates each at or above `0.90`, and no human
   safety rejection.
7. Release Manifest whose `model_registry_id`, adapter digest, generation
   profile identity, locked-suite identity, Prompt hash, Response Schema hash,
   and `data_build_id` match the candidate exactly.
8. Staging acceptance artifact that replaces
   `ops/acceptance/blocked.staging-acceptance.json` with checksummed
   all-passed evidence.

## Runtime contract

The staging runtime must prove all of the following:

- authenticated OpenAI-compatible gateway endpoint
- configured timeout via `MEGURI_LLM_TIMEOUT_SECONDS`
- configured concurrency limit via `MEGURI_LLM_MAX_CONCURRENCY`
- candidate and last-good routing from `training/llm/gateway/routing_state.json`
- schema-invalid provider output remains fail closed
- `/ready` reflects the active registered model and adapter digest
- `/ready` verifies the generation profile ID/digest and the endpoint returns
  `X-Meguri-Generation-Profile-Id` plus
  `X-Meguri-Generation-Profile-SHA256`
- rollback to last-good does not rebuild the model

The staging runtime must not:

- expose an unauthenticated public inference endpoint
- mount private model weights onto the cloud host
- substitute a floating latest base model or tokenizer revision
- infer production readiness from staging success

## Promotion checklist

Record the exact evidence paths or URLs for:

- candidate registry entry:
- rollback registry entry:
- release manifest:
- full probe report:
- dataset manifest:
- quality report:
- validation selection:
- generation profile:
- generation profile SHA-256:
- locked-eval suite ID and manifest SHA-256:
- independent-suite validation SHA-256:
- locked eval report:
- comparison report:
- human review:
- staging acceptance artifact:
- authenticated `/ready` evidence:
- authenticated non-stream JSON evidence:
- authenticated SSE evidence:
- timeout and cancellation evidence:
- concurrency-limit evidence:
- schema fail-closed evidence:
- rollback rehearsal evidence:

## Decision rule

Promotion is blocked unless every identity field and evidence item above is
present and mutually consistent. A complete handoff permits staging routing of
the candidate only. Production remains a separate approval path with its own
gates, evidence, and rollback authorization.
