# Verification: run-local-text-training-smoke

Date: 2026-10-08. Fixed training commit: `7f4696e516e90fae8eb8f059d09dd7c30ddee274`.

| Dimension | Result |
| --- | --- |
| Completeness | 8/8 operational and delivery tasks completed, including this verification; no product delta specifications (`skip_specs: true`) |
| Correctness | L-001 full probe pass; real 50-step L-006 pass; 12 independent artifact/identity/isolation checks pass |
| Coherence | Existing BF16 model, approved dataset, trainer, gates and installed dependencies preserved; isolated clean checkout; documented single backend option |

## Completeness

- Installed package/environment lock and cached fixed revision inspected; baseline validator accepts 2626/566 counts and source hashes.
- Attached clean checkout used throughout; primary worktree was neither reset nor committed.
- Passing full probe saved, including complete 92-line pip freeze, model/tokenizer identity, forward/backward/mask/checkpointing/save/reload/JSON checks. Interrupted default-compiler probes retain timed-stack logs and explicitly interrupted status.
- Deterministic 160/40 subset trained for 50 optimizer steps with fixed padding 768 and assistant-token loss normalization. The normal trainer CLI returned exit 0 and `pass`; final manifest stage is `L1_smoke`.
- `checkpoint-25`, `checkpoint-50`, final adapter, trainer state, metrics and artifact hashes saved. Process exit verified; GPU use fell to 3124 MiB, other applications were retained.
- Chinese report with reproducible commands saved in `docs/training-smoke-attempt-2026-10-08.md`. Notion report, canonical B and existing P0 learning page read back with all four critical markers on each. All five previous flowchart bodies and the P0 21/21 evidence remain present.
- `openspec validate run-local-text-training-smoke --strict` passes. This report completes the final verification task.

## Correctness

Evidence root: `training/llm/artifacts/reports/training-smoke-20261008/`.

| Evidence | Observation |
| --- | --- |
| `full-bf16-eager-20261008.json` | Full pass; model/tokenizer revision `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`; 12.1007 GiB probe peak |
| Experiment `experiment_manifest.json` | Same model/config/data identities; 160 train, 40 validation; 50 steps; fixed pad 768; post-training JSON pass; locked eval not accessed |
| `trainer_state.json` | Actual global_step 50, epoch 2.5, finite logged training and validation losses |
| `artifact_inspection.json` | Existing `validate_smoke_report` accepts result; all 12 independent checks true; 496 finite saved tensors, 248 nonzero LoRA B tensors, no visual-layer adapters |
| `execution_settings.json` | Offline model settings and `UNSLOTH_COMPILE_DISABLE=1` retained; three probe attempts disclosed; experiment and audit pass |
| `notion_readback.json` | Three pages contain passing gate, precise final validation loss, adapter digest and compile flag; five existing graphs unchanged |

Training loss (ten-step logging windows): 2.042086 → 1.382485 → 1.283521 → 1.188549 → 1.148274. Validation loss: 1.192397 at step 25, 1.153058 at step 50. Whole-run mean training loss: 1.408983. Training peak allocation 11.222203 GiB is below the unchanged 14.5 GiB limit. Trainer duration 1382.738 seconds.

Final adapter: 129,934,448 bytes, SHA-256 `df0b14440888eb979c2980642fc0ab1288ac25dce0dcf2ee4295e94f32683cc4`. Independent inspection checks this saved file; baseline/config/probe hashes and clean Git identity also match.

Requirement/scenario delta coverage is intentionally skipped because this change operates the committed training pipeline and records results; no trainer/product behavior was modified. No additional unit tests are warranted for this operational experiment. Actual GPU execution and artifact/gate validation provide the relevant evidence.

## Coherence and scope boundaries

The one tested backend option disables Unsloth compilation optimization after repeated SymPy timed stacks; it does not quantize Qwen3.5, modify losses, install dependencies or relax validators. The alternative Qwen3 comparison route was not needed. Model weights were reused offline, and the user's choice to retain other GPU applications was respected.

Training used the approved B baseline, not revoked/unreviewed feedback. The primary P0 changes remain outside the fixed training checkout. Notion distinguishes this later training from the earlier P0 implementation phase.

No critical discrepancies found. The experiment objective is complete; this is not full training, persona/safety qualification, locked evaluation, a streaming-contract migration or model promotion. Those remain explicit follow-up gates in the report and Notion. The existing candidate/staging/provider configuration was not changed; no best checkpoint was selected. Keep the change unarchived and the clean checkout attached for reproducibility or later full training.
