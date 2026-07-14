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
