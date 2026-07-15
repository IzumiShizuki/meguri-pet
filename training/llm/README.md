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
accept `num_items_in_batch` directly.

Full training uses the same entry point without `--smoke`. Resume is explicit
and only accepts a checkpoint below the same experiment directory. Checkpoints
are ranked by frozen validation composite score plus the fixed synthetic safety
suite; locked eval is structurally excluded from selection:

```powershell
python -m training.llm.scripts.train --experiment-id <id> `
  --dataset-dir <dataset> --probe-report <probe> --allow-download
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

The environment Agent supplied `ops/contracts/llm-agent.environment-contract.json`.
The human-readable staging handoff now lives at
`docs/contracts/llm-staging-handoff.md`. It gates candidate staging routing
only; no code in this branch marks a model Production-ready.
