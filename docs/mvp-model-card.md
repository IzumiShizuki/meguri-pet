# Meguri text MVP model card

Status: experimental local MVP, not staging or production.

## Artifact

- Branch: `codex/mvp-auto-fit`
- Experiment: `meguri-qwen35-4b-mvp-20260718-v6`
- Base model: `Qwen/Qwen3.5-4B`
- Base revision: `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`
- Training config: `training/llm/configs/qwen35_4b_bf16_lora.yaml`
- Derived dataset: `meguri-text-sft-v1-532aca8b1a5d`
- Source build: `meguri_v2_02c3db0c507d7c2d`
- Quick-fit subset: 100 train / 20 validation rows, seed `3407`
- Safe checkpoint: `training/llm/artifacts/checkpoints/meguri-qwen35-4b-mvp-20260718-v6/checkpoint-25`
- Extracted adapter: `.../final_adapter`
- Provenance: `.../mvp_manifest.json`

The short run reached step 25 of a requested 50-step smoke budget and was
stopped at the resumable checkpoint to keep the MVP turnaround short. The
adapter is a real LoRA artifact, not a mock response. A separate local
inference smoke produced schema-valid JSON with `reply`, expression fields and
an empty `memory_candidates` array. The result is intentionally not a quality
claim: no locked evaluation, comparison gate or staging registry update was
performed.

## Reproduction

Use the dedicated environment and keep the pinned model local:

```powershell
$env:HF_HUB_OFFLINE='1'
$env:UNSLOTH_COMPILE_DISABLE='1'
$env:UNSLOTH_COMPILE_LOCATION='D:\environment\cache\meguri-llm'
D:\environment\anaconda3\envs\meguri-llm\python.exe -m training.llm.scripts.run_mvp `
  --experiment-id meguri-qwen35-4b-mvp-20260718-v6
```

The full probe report used for this artifact is
`training/llm/artifacts/reports/qwen35-full-probe-mvp-v5.json`. It records the
fixed model identity, CUDA/BF16 capability, assistant-only mask,
forward/backward, adapter save/reload and minimum JSON inference checks.

## Formal-training handoff

The canonical source exports and locked eval remain read-only. For a formal
run, keep the same config/model revision and rebuild or validate the derived
dataset before increasing samples/steps. Resume only from the checkpoint after
archiving the experimental `final_adapter` or into a copied experiment
directory; the trainer deliberately refuses to overwrite an existing final
adapter. Formal acceptance still requires frozen validation, safety/comparison
gates and an explicit locked-eval acknowledgement.

## GitHub and résumé boundary

The source branch can safely be described as a reproducible local Meguri text
model MVP with LoRA training, schema validation and artifact provenance. The
binary adapter is ignored by normal Git history because it is roughly 130 MB;
publish it with Git LFS or a GitHub Release if desired. Do not describe this
short run as production quality, hosted staging, or a completed full fine-tune.
