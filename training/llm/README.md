# Meguri text LLM pipeline

This directory implements Notion plan 17 for text-only model work. It does not
contain or invoke any TTS data, training, or inference code.

The execution order is fixed:

1. `L-001`: environment and exact-model compatibility probe.
2. `L-002` / `L-003`: deterministic read-only source conversion and quality
   gates.
3. `L-004`: frozen L0 evaluation on the locked set. Locked cases are not
   available to training or validation code.
4. `L-005` / `L-006`: reproducible LoRA/QLoRA training entry points and a
   100-200 sample smoke run.
5. `L-007` onward: full experiments, fixed evaluation, registry, and staging.

The authoritative source build is `meguri_v2_02c3db0c507d7c2d`. Canonical
data and `datasets/meguri/exports` are always treated as read-only. Derived
data is written below `training/llm/artifacts` and every derived dataset has
its own manifest and content-derived dataset ID.

The approved exports contain outfit codes `07` and `08` even though both are
disabled runtime outfits. They are retained to preserve the fixed GO counts
and deterministically labeled `private`; this does not enable either outfit,
because outfit eligibility remains an external runtime decision. Converted
rows record `interaction_mode_source=deterministic_outfit_map_v1`.

The GO exports also contain the legacy `voice_style=embarrassed`, which is not
part of the pinned runtime schema. The converter records and deterministically
normalizes it to `restrained`; source rows remain unchanged and the manifest and
quality report expose the normalization policy and counts.

The main model is the official `Qwen/Qwen3.5-4B` revision pinned in
`configs/qwen35_4b_bf16_lora.yaml`. Qwen3.5 is multimodal, but this pipeline
freezes every vision layer and trains only language attention/MLP modules.
The comparison model is `Qwen/Qwen3-4B-Instruct-2507` with NF4 QLoRA. The 8B
configuration remains disabled until its explicit gates are satisfied.

Run the non-downloading preflight with the project Python environment:

```powershell
python -m training.llm.scripts.probe_environment --mode static
```

The full probe requires a dedicated LLM environment and an explicit
`--allow-download`. It must pass before smoke or full training is allowed.
Every successful L-001 report also records the exact `python -m pip freeze`
environment lock inside the probe evidence; a probe without that snapshot is
not considered complete. Versioned probes, evaluations, and training runs fail
closed unless the Git worktree is clean and the recorded commit remains stable
for the complete operation.

## Reproducible commands

The Windows/Blackwell environment is isolated from the application environment.
Install the pinned CUDA wheel first, then the remaining lock:

```powershell
D:\environment\anaconda3\envs\meguri-llm\python.exe -m pip install `
  torch==2.8.0 torchvision==0.23.0 `
  --index-url https://download.pytorch.org/whl/cu128
D:\environment\anaconda3\envs\meguri-llm\python.exe -m pip install `
  -r training\llm\environment\requirements-windows-blackwell.txt
```

On Windows the pipeline automatically places TorchInductor and Triton caches
below `D:\environment\cache\meguri-llm` to avoid the native path-length limit.
Set `MEGURI_LLM_COMPILE_CACHE_ROOT` before launch only when a different short,
writable cache root is required. The resolved cache paths are recorded in the
L-001 report.

Run the exact-model probe and build the read-only-source-derived dataset:

```powershell
python -m training.llm.scripts.probe_environment --mode full --allow-download `
  --report training\llm\artifacts\reports\qwen35-full-probe.json
python -m training.llm.scripts.build_sft_dataset `
  --data-root D:\program\meguri-pet\datasets\meguri `
  --split-root D:\program\meguri-pet\data\meguri\aligned_v1\splits
```

The L-006 command enforces 100–200 train rows, 50–100 optimizer steps, a
passing full probe, assistant-only labels, EOS/JSON boundaries and a
post-training Schema-valid generation:

```powershell
python -m training.llm.scripts.run_smoke `
  --experiment-id qwen35-4b-smoke-s3407 `
  --dataset-dir <derived-dataset-directory> `
  --probe-report <passing-full-probe-report> `
  --input-pad-length 768 --allow-download
```

The deterministic 160/40 L-006 subset currently spans 652..755 tokens. Smoke
training requires fixed padding to 768 so Windows/Triton compiles one training
shape; the observed maxima and requested pad length are recorded in the smoke
dataset and experiment manifests. The command fails rather than truncating a
sample or silently returning to variable shapes.

Training uses the Transformers causal-LM loss with the accumulated assistant
token count supplied by `SFTTrainer`. This keeps loss normalization correct
across eight microbatches even though Qwen3.5's forward signature does not
accept `num_items_in_batch` directly. Evaluation has no accumulated item count,
so it derives the denominator from the current batch's non-ignored assistant
labels instead.

Full training uses the same entry point without `--smoke`. Resume is explicit
and only accepts a checkpoint below the same experiment directory. Checkpoints
are ranked by frozen validation composite score plus the fixed synthetic safety
suite; locked eval is structurally excluded from selection:

Local validation and safety runs require an explicit fixed
`--input-pad-length`. Both refuse dirty or changing Git worktrees, and their
reports record the exact evaluation commit and framework versions.
Validation-only v2 decoding experiments may additionally set bounded
`--repetition-penalty` and `--no-repeat-ngram-size`; both values are recorded
in backend metadata. `--force-json-object-start` may constrain the first
generated tokens to the tokenizer's encoded `{"` prefix without repairing
output after generation. These controls must not be tuned from locked-eval
failure content.

The validation-selected profile is frozen in
`configs/qwen35_4b_lora_decode_v2.yaml`. Its status is deliberately
`validation_selected`, not Staging-eligible: the profile must be measured once
on a newly and independently frozen locked set, then pass the frozen-rubric
human persona review. The previous 184-case locked result remains evidence for
the default v1 decode path and must not be reused to tune or approve v2.
The file is validated against the pinned generation-profile contract and binds
the base/tokenizer revisions, adapter digest, generation controls, validation
evidence, safety evidence, and previous locked-suite exclusion. Once frozen,
evaluation and inference must use `--generation-profile` instead of repeating
the controls as command-line overrides.

```powershell
python -m training.llm.scripts.train --experiment-id <id> `
  --dataset-dir <dataset> --probe-report <probe> `
  --smoke-report <passing-L-006-experiment-manifest> `
  --input-pad-length 768 --allow-download
python -m training.llm.scripts.resume --experiment-id <id> `
  --dataset-dir <dataset> --probe-report <probe> `
  --resume-from-checkpoint <experiment-checkpoint>
```

Run locked eval only after the model/config is frozen. The acknowledgement is
mandatory and the committed fixture manifest pins all 184 case hashes:

```powershell
python -m training.llm.eval.run_locked_eval `
  --run-id <frozen-run-id> --run-kind post_train `
  --eval-root D:\program\meguri-pet\datasets\meguri\exports\eval `
  --rag-jsonl D:\program\meguri-pet\datasets\meguri\exports\rag\chunks_train.jsonl `
  --train-jsonl <derived-train-jsonl> --backend local --config <config> `
  --adapter <selected-adapter> --allow-download --input-pad-length 1152 `
  --acknowledge-locked-eval-is-evaluation-only
```

## Independent v2 locked evaluation and human review

The next v2 measurement requires an independently created and committed
manifest with a new `suite_id`; the profile explicitly excludes
`meguri-locked-eval-v1` and its frozen input-hash identity, so merely renaming
the old suite is rejected. A manifest supplied outside the checkout or left
untracked is also rejected. The new suite is not comparable with the old L0 reports,
so run all three paths against the same new manifest and inputs:

The suite must be prepared and approved outside the training/tuning role. The
freeze tool reads the candidate held-out files only to produce digests and
zero-overlap counts; it does not export their content. It requires a new source
build identity, distinct preparer/approver identities, and zero sample, input,
full-case, scene, and normalized near-input overlap with train/validation and
the previous locked set. Near-input rejection uses the frozen `0.95` similarity
threshold recorded in the manifest:

```powershell
python -m training.llm.eval.locked_suite `
  --suite-id <new-suite-id> --source-build-id <new-eval-source-build-id> `
  --eval-root <independent-new-eval-root> `
  --dataset-dir <derived-release-dataset> `
  --previous-locked-manifest training\llm\eval\fixtures\locked_eval_manifest.json `
  --previous-locked-eval-root <previous-locked-eval-root> `
  --rag-jsonl <frozen-rag-jsonl> `
  --prepared-by <independent-preparer-id> --approved-by <independent-approver-id> `
  --source-authority <heldout-source-authority> `
  --output training\llm\eval\fixtures\<new-suite-manifest>.json `
  --acknowledge-independent-freeze-and-non-tuning
```

The independent party must review and commit the generated v2 manifest before
the training/evaluation operator can launch a run. The manifest contains only
file/content-set digests, overlap counts, and declarations—not case text.

1. Base L0 without RAG and without an adapter.
2. Prompt+RAG L0 without an adapter.
3. The exported adapter with the frozen v2 generation profile.

Each command must pass the same `--locked-manifest`, `--eval-root`, fixed Prompt,
Response Schema, and `--input-pad-length 1152`. The candidate command adds:

```powershell
python -m training.llm.eval.run_locked_eval `
  --run-id <new-suite-candidate-run> --run-kind post_train `
  --locked-manifest <committed-new-suite-manifest> `
  --eval-root <independent-new-eval-root> --rag-jsonl <frozen-rag-jsonl> `
  --suite-rag-jsonl <frozen-rag-jsonl> --dataset-dir <derived-release-dataset> `
  --previous-locked-manifest training\llm\eval\fixtures\locked_eval_manifest.json `
  --previous-locked-eval-root <previous-locked-eval-root> `
  --train-jsonl <derived-train-jsonl> --backend local `
  --config training\llm\configs\qwen35_4b_bf16_lora.yaml `
  --adapter <exported-adapter> `
  --generation-profile training\llm\configs\qwen35_4b_lora_decode_v2.yaml `
  --input-pad-length 1152 --acknowledge-locked-eval-is-evaluation-only
```

Run the frozen safety suite with the same profile. Comparison fails closed
unless the candidate and safety reports use the same profile and all L0 and
candidate reports use the same locked-suite manifest, input hashes, and passing
independence-validation digest.

After automatic gates pass, create the frozen-rubric review packet. The packet
contains model outputs and coarse relationship/mode context but omits source
sample IDs; its content remains measurement-only:

```powershell
python -m training.llm.eval.human_review prepare `
  --locked-eval-dir <new-suite-candidate-output> `
  --packet <review-packet.json> --review-template <review-form.json>
python -m training.llm.eval.human_review finalize `
  --packet <review-packet.json> --completed-review <completed-review-form.json> `
  --output <human-review-result.json>
```

Finalization requires all 184 ratings, reviewer identity/timestamp, an
independence declaration, and a declaration that locked content was not used
for tuning. Approval requires persona score at least `0.90`, JP and ZH
naturalness rates each at least `0.90`, and zero human safety rejections.

Only after the comparison gate passes may the same adapter be registered under
a distinct profile-bound deployment identity. Registration verifies the new
suite, manifest, profile, adapter, human-review-backed comparison, and rollback
target together:

```powershell
python -m training.llm.scripts.register_model `
  --export-dir <exported-adapter> --experiment-manifest <experiment-manifest> `
  --validation-selection <validation-selection> `
  --locked-eval-report <new-suite-candidate-report> `
  --comparison-report <new-suite-comparison-report> `
  --generation-profile training\llm\configs\qwen35_4b_lora_decode_v2.yaml `
  --model-id <distinct-profile-bound-model-id> `
  --status staging_candidate --parent-model-id <evaluated-v1-model-id> `
  --rollback-model-id <explicit-last-good-model-id>
```

The frozen 184-case suite currently ranges from 896 to 1143 input tokens when
Prompt + RAG is assembled. Local comparisons use left padding to 1152 tokens
so TorchInductor sees one input shape; the report retains each unpadded input
length. Base, Prompt + RAG, and adapter runs must use the same pad length.

## Staging boundary

The gateway is authenticated, OpenAI-compatible and validates the complete
Meguri response before sending either JSON or SSE. It enforces pinned registry
digests, Prompt hash, timeout, concurrency, generation cancellation and
candidate/last-good routing. The checked-in routing state is intentionally
unconfigured and fail-closed. It cannot become ready until evaluated model
artifacts and a last-good registry entry exist. Switching back to last-good uses
`training.llm.scripts.switch_staging_model` and does not rebuild a model.
Profile-bound candidates receive a distinct deployment model ID. Registry and
Gateway verify the profile ID and SHA-256 together with the adapter digest and
base/tokenizer revisions; the Gateway executes the pinned controls and returns
`X-Meguri-Generation-Profile-Id` and
`X-Meguri-Generation-Profile-SHA256`. Existing v1 entries with null profile
fields retain the original default decode behavior.

The environment Agent supplied `ops/contracts/llm-agent.environment-contract.json`.
The human-readable staging handoff now lives at
`docs/contracts/llm-staging-handoff.md`. It gates candidate staging routing
only; no code in this branch marks a model Production-ready.
