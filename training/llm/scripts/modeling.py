from __future__ import annotations

from pathlib import Path
from typing import Any

from .common import PipelineError


def resolve_model_snapshot(config: dict[str, Any], *, allow_download: bool) -> Path:
    model = config["model"]
    repo_id = str(model["repo_id"])
    revision = str(model["revision"])
    try:
        from huggingface_hub import snapshot_download
    except ImportError as exc:
        raise PipelineError("huggingface-hub is required for the full probe") from exc
    try:
        snapshot = snapshot_download(
            repo_id=repo_id,
            revision=revision,
            local_files_only=not allow_download,
        )
    except Exception as exc:  # the hub exposes several version-specific errors
        mode = "download enabled" if allow_download else "local cache only"
        raise PipelineError(
            f"cannot resolve pinned model {repo_id}@{revision} ({mode}); "
            "the pipeline will not substitute another model or revision"
        ) from exc
    return Path(snapshot)


def load_base_model(
    config: dict[str, Any],
    *,
    allow_download: bool,
) -> tuple[Any, Any, Any]:
    """Load the exact pinned base model without attaching an adapter."""

    try:
        import torch
    except ImportError as exc:
        raise PipelineError("PyTorch is required for the full probe") from exc

    model_config = config["model"]
    training = config["training"]
    snapshot = resolve_model_snapshot(config, allow_download=allow_download)
    dtype = torch.bfloat16 if model_config.get("dtype") == "bfloat16" else torch.float16
    loader = model_config.get("loader")

    if loader == "unsloth_vision":
        try:
            from unsloth import FastVisionModel
        except ImportError as exc:
            raise PipelineError("Unsloth with Qwen3.5 support is required") from exc
        model, processor = FastVisionModel.from_pretrained(
            model_name=str(snapshot),
            load_in_4bit=bool(model_config.get("load_in_4bit")),
            dtype=dtype,
            use_gradient_checkpointing=training.get("gradient_checkpointing", "unsloth"),
        )
        return model, processor, FastVisionModel

    if loader == "unsloth_language":
        try:
            from unsloth import FastLanguageModel
        except ImportError as exc:
            raise PipelineError("Unsloth is required for Qwen3 training") from exc
        model, tokenizer = FastLanguageModel.from_pretrained(
            model_name=str(snapshot),
            max_seq_length=int(training["max_seq_length"]),
            dtype=dtype,
            load_in_4bit=bool(model_config.get("load_in_4bit")),
        )
        return model, tokenizer, FastLanguageModel

    raise PipelineError(f"unsupported model loader: {loader}")


def attach_lora(config: dict[str, Any], model: Any, loader_class: Any) -> Any:
    """Attach only the pinned text LoRA modules to an already loaded base."""

    model_config = config["model"]
    training = config["training"]
    lora = config["lora"]
    common = {
        "r": int(lora["r"]),
        "target_modules": lora.get("target_modules"),
        "lora_alpha": int(lora["alpha"]),
        "lora_dropout": float(lora["dropout"]),
        "bias": str(lora.get("bias", "none")),
        "random_state": int(training["seed"]),
        "use_rslora": False,
        "loftq_config": None,
    }
    if model_config.get("loader") == "unsloth_vision":
        return loader_class.get_peft_model(
            model,
            finetune_vision_layers=False,
            finetune_language_layers=True,
            finetune_attention_modules=True,
            finetune_mlp_modules=True,
            **common,
        )
    return loader_class.get_peft_model(
        model,
        use_gradient_checkpointing=training.get("gradient_checkpointing", "unsloth"),
        **common,
    )


def load_model_with_lora(
    config: dict[str, Any],
    *,
    allow_download: bool,
) -> tuple[Any, Any, Any]:
    """Load the exact pinned model and attach only the configured LoRA modules."""

    model, tokenizer, loader_class = load_base_model(config, allow_download=allow_download)
    return attach_lora(config, model, loader_class), tokenizer, loader_class


def assert_text_only_trainable_parameters(model: Any) -> dict[str, int]:
    trainable = 0
    total = 0
    forbidden: list[str] = []
    for name, parameter in model.named_parameters():
        count = int(parameter.numel())
        total += count
        if parameter.requires_grad:
            trainable += count
            lowered = name.lower()
            if any(token in lowered for token in ("vision", "visual", "image", "patch_embed")):
                forbidden.append(name)
    if forbidden:
        preview = ", ".join(forbidden[:5])
        raise PipelineError(f"vision parameters are trainable: {preview}")
    if trainable <= 0:
        raise PipelineError("LoRA attachment produced no trainable parameters")
    return {"trainable_parameters": trainable, "total_parameters": total}
