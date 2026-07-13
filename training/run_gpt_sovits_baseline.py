from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

from training.common import GPT_SOVITS_PYTHON, GPT_SOVITS_ROOT, REPORT_ROOT, run_command, utc_now, write_json
from training.prepare_gpt_sovits import prepare


def posix(path: Path) -> str:
    return path.resolve().as_posix()


def gate_or_raise() -> tuple[dict[str, Any], dict[str, Any]]:
    verification = json.loads((REPORT_ROOT / "training_input_verification.json").read_text(encoding="utf-8"))
    acoustic = json.loads((REPORT_ROOT / "tts_acoustic_gate.json").read_text(encoding="utf-8"))
    if verification.get("decision") != "GO":
        raise RuntimeError("training input verification is not GO")
    if acoustic.get("decision") not in {"GO", "CONDITIONAL_GO"}:
        raise RuntimeError("TTS acoustic gate is not GO or CONDITIONAL_GO")
    if int(acoustic.get("manual_review_count") or 0) < 100:
        raise RuntimeError("100 human listening decisions are required")
    return verification, acoustic


def stage_paths(work_root: Path, config: dict[str, Any]) -> dict[str, Path]:
    output_root = Path(config["output_root"])
    return {
        "work_root": work_root,
        "audio_root": work_root / "audio",
        "train_list": work_root / "filelists" / "train.list",
        "text": work_root / "2-name2text.txt",
        "semantic": work_root / "6-name2semantic.tsv",
        "output_root": output_root,
        "logs": output_root / "logs" / "orchestrator",
        "s1_config": output_root / "training_s1.yaml",
        "s2_config": output_root / "training_s2.json",
    }


def make_s1_config(paths: dict[str, Path], config: dict[str, Any]) -> None:
    t = config["training_policy"]
    experiment_name = str(t.get("experiment_name", "meguri_baseline_001"))
    (paths["output_root"] / "checkpoints" / "gpt_weights").mkdir(parents=True, exist_ok=True)
    lines = [
        "train:",
        "  seed: 3407",
        f"  epochs: {int(t['gpt_epochs'])}",
        f"  batch_size: {int(t['initial_batch_size'])}",
        "  save_every_n_epoch: 1",
        "  precision: 16-mixed",
        "  gradient_clip: 1.0",
        "  if_save_every_weights: true",
        "  if_save_latest: false",
        "  if_dpo: false",
        f"  half_weights_save_dir: '{posix(paths['output_root'] / 'checkpoints' / 'gpt_weights')}'",
        f"  exp_name: '{experiment_name}'",
        "optimizer:",
        "  lr: 0.01",
        "  lr_init: 0.00001",
        "  lr_end: 0.0001",
        "  warmup_steps: 2000",
        "  decay_steps: 40000",
        "data:",
        "  max_eval_sample: 8",
        "  max_sec: 54",
        "  num_workers: 2",
        "  pad_val: 1024",
        "model:",
        "  vocab_size: 1025",
        "  phoneme_vocab_size: 732",
        "  embedding_dim: 512",
        "  hidden_dim: 512",
        "  head: 16",
        "  linear_units: 2048",
        "  n_layer: 24",
        "  dropout: 0",
        "  EOS: 1024",
        "  random_bert: 0",
        "inference:",
        "  top_k: 15",
        f"pretrained_s1: '{posix(GPT_SOVITS_ROOT / 'GPT_SoVITS' / 'pretrained_models' / 's1v3.ckpt')}'",
        f"train_semantic_path: '{posix(paths['semantic'])}'",
        f"train_phoneme_path: '{posix(paths['text'])}'",
        f"output_dir: '{posix(paths['output_root'] / 'checkpoints' / 'gpt')}'",
    ]
    paths["s1_config"].parent.mkdir(parents=True, exist_ok=True)
    paths["s1_config"].write_text("\n".join(lines) + "\n", encoding="utf-8")


def make_s2_config(paths: dict[str, Path], config: dict[str, Any]) -> None:
    source = GPT_SOVITS_ROOT / "GPT_SoVITS" / "configs" / "s2v2Pro.json"
    data = json.loads(source.read_text(encoding="utf-8"))
    t = config["training_policy"]
    experiment_name = str(t.get("experiment_name", "meguri_baseline_001"))
    (paths["output_root"] / "checkpoints" / "sovits").mkdir(parents=True, exist_ok=True)
    (paths["output_root"] / "checkpoints" / "sovits_weights").mkdir(parents=True, exist_ok=True)
    (paths["work_root"] / "logs_s2_v2Pro").mkdir(parents=True, exist_ok=True)
    data["train"].update(
        {
            "seed": int(t["random_seed"]),
            "epochs": int(t["sovits_epochs"]),
            "batch_size": int(t["initial_batch_size"]),
            "fp16_run": bool(t["fp16"]),
            "save_every_epoch": 1,
            "if_save_every_weights": True,
            "if_save_latest": False,
            "gpu_numbers": "0",
            "grad_ckpt": False,
            "pretrained_s2G": posix(GPT_SOVITS_ROOT / "GPT_SoVITS" / "pretrained_models" / "v2Pro" / "s2Gv2Pro.pth"),
            "pretrained_s2D": posix(GPT_SOVITS_ROOT / "GPT_SoVITS" / "pretrained_models" / "v2Pro" / "s2Dv2Pro.pth"),
        }
    )
    data["model"]["version"] = "v2Pro"
    data["data"]["exp_dir"] = posix(paths["work_root"])
    data["s2_ckpt_dir"] = posix(paths["output_root"] / "checkpoints" / "sovits")
    data["save_weight_dir"] = posix(paths["output_root"] / "checkpoints" / "sovits_weights")
    data["name"] = experiment_name
    data["version"] = "v2Pro"
    paths["s2_config"].parent.mkdir(parents=True, exist_ok=True)
    paths["s2_config"].write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def merge_part(path: Path, pattern: str, header: str | None = None) -> None:
    parts = sorted(path.parent.glob(pattern))
    if not parts:
        raise RuntimeError(f"missing preprocessing outputs: {pattern}")
    content: list[str] = []
    if header:
        content.append(header)
    for part in parts:
        content.extend(line for line in part.read_text(encoding="utf-8").splitlines() if line.strip())
    path.write_text("\n".join(content) + "\n", encoding="utf-8")
    for part in parts:
        if part != path:
            part.unlink(missing_ok=True)


def command_env(paths: dict[str, Path], config: dict[str, Any]) -> dict[str, str]:
    env = os.environ.copy()
    ffmpeg_dir = Path(r"D:\environment\ffmpeg\bin")
    if not (ffmpeg_dir / "ffmpeg.exe").is_file():
        raise RuntimeError(f"ffmpeg is missing from the canonical environment: {ffmpeg_dir}")
    env.update(
        {
            "PYTHONUNBUFFERED": "1",
            "PYTHONIOENCODING": "utf-8",
            "PYTHONUTF8": "1",
            "TORCH_FORCE_NO_WEIGHTS_ONLY_LOAD": "1",
            "version": "v2Pro",
            "is_half": "True",
            "_CUDA_VISIBLE_DEVICES": "0",
            "i_part": "0",
            "all_parts": "1",
            "inp_text": str(paths["train_list"]),
            "inp_wav_dir": str(paths["audio_root"]),
            "exp_name": str(config.get("training_policy", {}).get("experiment_name", "meguri_baseline_001")),
            "opt_dir": str(paths["work_root"]),
            "bert_pretrained_dir": str(GPT_SOVITS_ROOT / "GPT_SoVITS" / "pretrained_models" / "chinese-roberta-wwm-ext-large"),
            "cnhubert_base_dir": str(GPT_SOVITS_ROOT / "GPT_SoVITS" / "pretrained_models" / "chinese-hubert-base"),
            "pretrained_s2G": str(GPT_SOVITS_ROOT / "GPT_SoVITS" / "pretrained_models" / "v2Pro" / "s2Gv2Pro.pth"),
            "s2config_path": str(GPT_SOVITS_ROOT / "GPT_SoVITS" / "configs" / "s2v2Pro.json"),
            "sv_path": str(GPT_SOVITS_ROOT / "GPT_SoVITS" / "pretrained_models" / "sv" / "pretrained_eres2netv2w24s4ep4.ckpt"),
            "PATH": str(ffmpeg_dir) + os.pathsep + env.get("PATH", ""),
        }
    )
    return env


def run_process(name: str, args: list[str], cwd: Path, env: dict[str, str], log_dir: Path) -> None:
    log_dir.mkdir(parents=True, exist_ok=True)
    log_path = log_dir / f"{name}.log"
    with log_path.open("w", encoding="utf-8") as log:
        log.write(f"started_utc={utc_now()}\ncommand={args!r}\n")
        process = subprocess.Popen(
            args,
            cwd=str(cwd),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        assert process.stdout is not None
        for line in process.stdout:
            log.write(line)
            console_encoding = sys.stdout.encoding or "utf-8"
            safe_line = line.encode(console_encoding, errors="replace").decode(console_encoding)
            print(safe_line, end="")
        code = process.wait()
        log.write(f"finished_utc={utc_now()}\nreturncode={code}\n")
    if code != 0:
        raise RuntimeError(f"GPT-SoVITS stage failed: {name} ({code})")


def preprocess(paths: dict[str, Path], config: dict[str, Any], execute: bool) -> list[list[str]]:
    env = command_env(paths, config)
    scripts = GPT_SOVITS_ROOT / "GPT_SoVITS" / "prepare_datasets"
    commands = [
        [str(GPT_SOVITS_PYTHON), "-s", str(scripts / "1-get-text.py")],
        [str(GPT_SOVITS_PYTHON), "-s", str(scripts / "2-get-hubert-wav32k.py")],
        [str(GPT_SOVITS_PYTHON), "-s", str(scripts / "2-get-sv.py")],
        [str(GPT_SOVITS_PYTHON), "-s", str(scripts / "3-get-semantic.py")],
    ]
    if not execute:
        return commands
    for index, command in enumerate(commands, start=1):
        run_process(f"preprocess_{index}", command, GPT_SOVITS_ROOT, env, paths["logs"])
        if index == 1:
            merge_part(paths["text"], "2-name2text-*.txt")
        if index == 4:
            merge_part(paths["semantic"], "6-name2semantic-*.tsv", header="item_name\tsemantic_audio")
    expected = sum(1 for line in paths["train_list"].read_text(encoding="utf-8").splitlines() if line.strip())
    actual = {
        "text": sum(1 for line in paths["text"].read_text(encoding="utf-8").splitlines() if line.strip()),
        "semantic": max(0, sum(1 for line in paths["semantic"].read_text(encoding="utf-8").splitlines() if line.strip()) - 1),
        "hubert": len(list((paths["work_root"] / "4-cnhubert").glob("*.pt"))),
        "wav32k": len(list((paths["work_root"] / "5-wav32k").glob("*"))),
        "speaker_vectors": len(list((paths["work_root"] / "7-sv_cn").glob("*.pt"))),
    }
    incomplete = {name: count for name, count in actual.items() if count != expected}
    if incomplete:
        raise RuntimeError(f"preprocessing output count mismatch: expected={expected}, actual={actual}")
    return commands


def training_commands(paths: dict[str, Path], config: dict[str, Any]) -> list[tuple[str, list[str]]]:
    make_s1_config(paths, config)
    make_s2_config(paths, config)
    return [
        (
            "train_gpt",
            [str(GPT_SOVITS_PYTHON), "-s", str(GPT_SOVITS_ROOT / "GPT_SoVITS" / "s1_train.py"), "--config_file", str(paths["s1_config"])],
        ),
        (
            "train_sovits",
            [str(GPT_SOVITS_PYTHON), "-s", str(GPT_SOVITS_ROOT / "GPT_SoVITS" / "s2_train.py"), "--config", str(paths["s2_config"])],
        ),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description="Guarded GPT-SoVITS baseline runner")
    parser.add_argument("--stage", choices=["preprocess", "gpt", "sovits", "all"], default="all")
    parser.add_argument("--execute", action="store_true", help="actually run GPU preprocessing/training")
    parser.add_argument("--config", type=Path, default=Path("configs") / "tts_baseline.json")
    args = parser.parse_args()
    try:
        _, _ = gate_or_raise()
        config_path = args.config.resolve()
        config = json.loads(config_path.read_text(encoding="utf-8"))
        work_root = prepare(config_path)
        paths = stage_paths(work_root, config)
        commands: list[Any] = []
        if args.stage in {"preprocess", "all"}:
            commands.extend(preprocess(paths, config, args.execute))
        if args.stage in {"gpt", "sovits", "all"}:
            if args.stage != "all" and not (paths["text"].is_file() and paths["semantic"].is_file()):
                raise RuntimeError("preprocessing outputs are missing; run --stage preprocess first")
            train = training_commands(paths, config)
            selected = train if args.stage == "all" else [item for item in train if item[0] == ("train_gpt" if args.stage == "gpt" else "train_sovits")]
            commands.extend(command for _, command in selected)
            if args.execute:
                env = command_env(paths, config)
                for name, command in selected:
                    run_process(name, command, GPT_SOVITS_ROOT, env, paths["logs"])
        plan = {"build_id": config["build_id"], "stage": args.stage, "execute": args.execute, "commands": commands, "generated_utc": utc_now()}
        write_json(paths["output_root"] / "orchestration_plan.json", plan)
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return 0
    except RuntimeError as exc:
        print(f"GPT-SoVITS runner blocked: {exc}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
