## Context

See proposal.md. The machine has the existing meguri-llm environment, RTX 5060 Ti 16GB, and cached Qwen3.5-4B revision 851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a. The primary checkout contains unrelated and P0 uncommitted changes. The approved derived baseline remains 2626 training / 566 validation records. The synthetic P0 feedback was withdrawn and is not training input. Other applications already use about 7GB of VRAM.

## Goals / Non-Goals

**Goals:** Demonstrate a reproducible bounded local training attempt with preserved preflight, metrics, adapter output and truthful gate results.

**Non-Goals:** Change the trainer or its gates, claim persona improvement from training loss alone, tune on locked evaluation, label withdrawn/unreviewed feedback, register/promote the candidate, or replace the desktop provider.

## Decisions

1. Use the attached clean worktree at 7f4696e516e90fae8eb8f059d09dd7c30ddee274 and installed meguri-llm interpreter. Planning stays in the primary checkout; fixed training code is independently clean. No shared changes are committed or reset.
2. Validate the existing approved dataset by hashes/counts, then use the trainer's deterministic 160 training / 40 validation subset and 50 optimizer steps, batch size 1, accumulation 8, fixed input padding 768. Do not read locked-eval cases. Baseline files remain unchanged.
3. First use the documented BF16 LoRA mainline and the cached pinned model, with Hugging Face/Transformers offline environment settings. A successful full L-001 report with environment lock is required before training; do not substitute old MVP manifests for that gate.
4. Preserve other GPU applications. If available VRAM blocks the BF16 path, the already documented pinned Qwen3-4B NF4 comparison route may be separately probed and attempted; it is a distinct model/config and requires its own passing report. Do not silently quantize Qwen3.5 or weaken its validator.
5. Store experiments and logs in ignored artifact directories with unique IDs. Keep failed reports and checkpoints. A completed optimization run with invalid post-training JSON is a failed smoke gate, not a successful usable model. Results and remaining steps are recorded locally and in the existing Notion learning page.
6. If the default compiler stalls, collect timed stacks before changing the backend. The observed preflight repeatedly entered SymPy symbolic work after model loading. Test the installed `UNSLOTH_COMPILE_DISABLE=1` execution option while retaining the same BF16 model, full probe checks and training gate. Preserve and disclose the backend option in experiment evidence; do not alter installed dependencies or validation code.

## Risks / Trade-offs

- Shared VRAM can make the mainline fail or page slowly -> retain diagnostics, avoid terminating unrelated applications, and report the exact blocker if neither approved path can run.
- Small smoke loss is not deployment evidence -> require later validation/safety, independent held-out evaluation and persona review before any model routing change.
- Clean training code excludes the uncommitted feedback bridge -> this attempt uses only the already approved baseline; future reviewed-feedback training uses its separately validated manifest after a reviewed code snapshot exists.
