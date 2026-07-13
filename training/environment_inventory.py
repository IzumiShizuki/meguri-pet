from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
from pathlib import Path
from typing import Any

from training.common import (
    BUILD_ID,
    DATASET_ROOT,
    FFMPEG,
    FFPROBE,
    GIT,
    GPT_SOVITS_PYTHON,
    GPT_SOVITS_ROOT,
    MODEL_ROOT,
    PROJECT_ROOT,
    PYTHON_314,
    REPORT_ROOT,
    ensure_output_dirs,
    file_tree_bytes,
    run_command,
    sha256_file,
    utc_now,
    write_json,
)


SELECTED_MODEL_DEPENDENCIES = [
    "GPT_SoVITS/pretrained_models/v2Pro/s2Gv2Pro.pth",
    "GPT_SoVITS/pretrained_models/v2Pro/s2Dv2Pro.pth",
    "GPT_SoVITS/pretrained_models/s1v3.ckpt",
    "GPT_SoVITS/pretrained_models/chinese-hubert-base/config.json",
    "GPT_SoVITS/pretrained_models/chinese-hubert-base/preprocessor_config.json",
    "GPT_SoVITS/pretrained_models/chinese-hubert-base/pytorch_model.bin",
    "GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large/config.json",
    "GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large/pytorch_model.bin",
    "GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large/tokenizer.json",
    "GPT_SoVITS/configs/s2v2Pro.json",
    "GPT_SoVITS/configs/s1longer-v2.yaml",
]


def command_version(executable: Path | str, *args: str) -> dict[str, Any]:
    return run_command([executable, *args], timeout=120)


def disk_info(path: Path) -> dict[str, Any]:
    anchor = path if path.exists() else path.parent
    while not anchor.exists() and anchor.parent != anchor:
        anchor = anchor.parent
    usage = shutil.disk_usage(anchor)
    return {
        "path": str(path),
        "total_bytes": usage.total,
        "used_bytes": usage.used,
        "free_bytes": usage.free,
        "free_gib": round(usage.free / (1024**3), 3),
    }


def collect_inventory() -> dict[str, Any]:
    ensure_output_dirs()
    git_version = command_version(GIT, "--version")
    ffmpeg_version = command_version(FFMPEG, "-version")
    ffprobe_version = command_version(FFPROBE, "-version")
    conda = Path(r"D:\environment\miniconda3\Scripts\conda.exe")
    conda_version = command_version(conda, "--version")
    python_314 = command_version(PYTHON_314, "--version")
    torch_probe = run_command(
        [
            GPT_SOVITS_PYTHON,
            "-c",
            (
                "import json,sys,torch;"
                "print(json.dumps({'python':sys.version,'torch':torch.__version__,"
                "'cuda_runtime':torch.version.cuda,'cuda_available':torch.cuda.is_available(),"
                "'gpu':torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,"
                "'capability':torch.cuda.get_device_capability(0) if torch.cuda.is_available() else None,"
                "'vram_bytes':torch.cuda.get_device_properties(0).total_memory if torch.cuda.is_available() else 0,"
                "'smoke_sum':float(torch.ones((1024,1024),device='cuda').sum().item()) if torch.cuda.is_available() else None}))"
            ),
        ],
        timeout=120,
    )
    try:
        torch_info = json.loads(torch_probe["stdout"].splitlines()[-1])
    except (json.JSONDecodeError, IndexError):
        torch_info = {"error": torch_probe["stderr"] or torch_probe["stdout"]}

    nvidia_query = run_command(
        [
            "nvidia-smi",
            "--query-gpu=name,driver_version,memory.total,memory.free,compute_cap",
            "--format=csv,noheader,nounits",
        ],
        timeout=120,
    )
    ffmpeg_smoke = run_command(
        [
            FFMPEG,
            "-hide_banner",
            "-nostdin",
            "-v",
            "error",
            "-xerror",
            "-i",
            str(PROJECT_ROOT / "data" / "meguri" / "assets" / "voice_safe" / "MGR050498.ogg"),
            "-f",
            "null",
            "NUL",
        ],
        timeout=60,
    )
    git_commit = command_version(GIT, "-C", str(GPT_SOVITS_ROOT), "rev-parse", "HEAD")
    git_describe = command_version(GIT, "-C", str(GPT_SOVITS_ROOT), "describe", "--tags", "--always", "--dirty")
    git_status = command_version(GIT, "-C", str(GPT_SOVITS_ROOT), "status", "--short", "--branch")
    git_remote = command_version(GIT, "-C", str(GPT_SOVITS_ROOT), "remote", "get-url", "origin")

    dependencies: list[dict[str, Any]] = []
    for relative in SELECTED_MODEL_DEPENDENCIES:
        path = GPT_SOVITS_ROOT / relative
        item = {
            "path": str(path),
            "relative_path": relative,
            "exists": path.is_file(),
            "bytes": path.stat().st_size if path.is_file() else None,
            "sha256": sha256_file(path) if path.is_file() else None,
        }
        dependencies.append(item)

    missing_dependencies = [item["relative_path"] for item in dependencies if not item["exists"]]
    dataset_bytes = file_tree_bytes(DATASET_ROOT)
    source_bytes = file_tree_bytes(PROJECT_ROOT / "data" / "meguri")
    framework_bytes = file_tree_bytes(GPT_SOVITS_ROOT)
    model_output_bytes = file_tree_bytes(MODEL_ROOT)
    inventory = {
        "build_id": BUILD_ID,
        "generated_utc": utc_now(),
        "platform": {
            "system": platform.system(),
            "release": platform.release(),
            "version": platform.version(),
            "machine": platform.machine(),
        },
        "gpu": {
            "nvidia_smi_query": nvidia_query,
            "torch": torch_info,
            "torch_cuda_smoke_passed": torch_info.get("smoke_sum") == 1048576.0,
        },
        "software": {
            "git": git_version,
            "ffmpeg": ffmpeg_version,
            "ffprobe": ffprobe_version,
            "conda": conda_version,
            "python_inventory": python_314,
            "gpt_sovits_python": str(GPT_SOVITS_PYTHON),
            "ffmpeg_decode_smoke": ffmpeg_smoke,
        },
        "gpt_sovits": {
            "root": str(GPT_SOVITS_ROOT),
            "origin": git_remote["stdout"],
            "commit": git_commit["stdout"],
            "describe": git_describe["stdout"],
            "status": git_status["stdout"],
            "dirty": "-dirty" in git_describe["stdout"] or bool(git_status["stdout"].splitlines()[1:]),
            "selected_version": "v2Pro",
            "selection_reason": "Local README recommends v1/v2/v2Pro for average-quality training data; v2Pro is already installed and fits 16GB VRAM.",
        },
        "model_dependencies": {
            "source": "GPT-SoVITS README pretrained model section; locally installed files only",
            "items": dependencies,
            "missing": missing_dependencies,
        },
        "storage": {
            "disk": disk_info(PROJECT_ROOT),
            "dataset_bytes": dataset_bytes,
            "source_data_bytes": source_bytes,
            "framework_bytes": framework_bytes,
            "model_output_bytes": model_output_bytes,
        },
        "compatibility": {
            "readme_tested_environment": "Python 3.11 / PyTorch 2.7.0 / CUDA 12.8",
            "installed_environment": (
                f"Python {str(torch_info.get('python', '')).split()[0]} / "
                f"PyTorch {torch_info.get('torch')} / CUDA {torch_info.get('cuda_runtime')}"
            ),
            "matches_tested_matrix": str(torch_info.get("torch", "")).startswith("2.7.0")
            and str(torch_info.get("python", "")).startswith("3.10") is False,
            "note": "Installed Python is 3.10 while the exact README matrix pairs PyTorch 2.7/CUDA 12.8 with Python 3.11. GPU smoke tests are required before training.",
        },
    }
    # Python 3.10 is supported by the project badge and install instructions, even if
    # this exact three-way combination is not listed in the tested matrix.
    inventory["compatibility"]["supported_components"] = True
    return inventory


def write_reports(inventory: dict[str, Any]) -> None:
    write_json(REPORT_ROOT / "software_inventory.json", inventory)
    checksum_lines = [
        f"{item['sha256']}  {item['relative_path']}"
        for item in inventory["model_dependencies"]["items"]
        if item["exists"]
    ]
    (REPORT_ROOT / "model_dependency_checksums.sha256").write_text(
        "\n".join(checksum_lines) + ("\n" if checksum_lines else ""), encoding="utf-8"
    )

    torch = inventory["gpu"]["torch"]
    storage = inventory["storage"]
    framework = inventory["gpt_sovits"]
    missing = inventory["model_dependencies"]["missing"]
    lines = [
        "# Environment Report",
        "",
        f"- Build ID: `{inventory['build_id']}`",
        f"- Generated UTC: `{inventory['generated_utc']}`",
        f"- GPU: `{torch.get('gpu')}`",
        f"- VRAM: `{round(int(torch.get('vram_bytes', 0)) / (1024**3), 2)} GiB`",
        f"- PyTorch: `{torch.get('torch')}`",
        f"- CUDA runtime: `{torch.get('cuda_runtime')}`",
        f"- CUDA available: `{torch.get('cuda_available')}`",
        f"- CUDA tensor smoke test: `{inventory['gpu'].get('torch_cuda_smoke_passed')}`",
        f"- FFmpeg decode smoke test: `{inventory['software']['ffmpeg_decode_smoke'].get('returncode') == 0}`",
        f"- GPT-SoVITS commit: `{framework['commit']}`",
        f"- GPT-SoVITS worktree dirty: `{framework['dirty']}`",
        f"- Selected framework version: `{framework['selected_version']}`",
        f"- Missing selected dependencies: `{len(missing)}`",
        f"- D drive free: `{storage['disk']['free_gib']} GiB`",
        "",
        "## Compatibility",
        "",
        f"- README tested reference: {inventory['compatibility']['readme_tested_environment']}",
        f"- Installed: {inventory['compatibility']['installed_environment']}",
        f"- Note: {inventory['compatibility']['note']}",
        "",
        "## Framework State",
        "",
        "The existing GPT-SoVITS repository contains user modifications. This training project records that state and does not reset, overwrite, pull or reinstall the framework.",
        "",
        "## Pretrained Model Provenance",
        "",
        "Selected dependency names follow the local official GPT-SoVITS README and `config.py`. No model was downloaded in this run. SHA-256 values are recorded in `reports/model_dependency_checksums.sha256`.",
        "",
        "## Storage",
        "",
        f"- Formal dataset: `{round(storage['dataset_bytes'] / (1024**3), 3)} GiB`",
        f"- Source data: `{round(storage['source_data_bytes'] / (1024**3), 3)} GiB`",
        f"- GPT-SoVITS installation: `{round(storage['framework_bytes'] / (1024**3), 3)} GiB`",
        f"- Existing Meguri model outputs: `{round(storage['model_output_bytes'] / (1024**3), 3)} GiB`",
    ]
    if missing:
        lines.extend(["", "## Blocking Missing Files", ""] + [f"- `{item}`" for item in missing])
    (REPORT_ROOT / "environment_report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Inventory the Meguri training environment")
    parser.parse_args()
    inventory = collect_inventory()
    write_reports(inventory)
    missing = inventory["model_dependencies"]["missing"]
    cuda_ok = bool(inventory["gpu"]["torch"].get("cuda_available"))
    cuda_smoke_ok = bool(inventory["gpu"].get("torch_cuda_smoke_passed"))
    ffmpeg_smoke_ok = inventory["software"]["ffmpeg_decode_smoke"].get("returncode") == 0
    print(
        f"environment inventory: cuda={cuda_ok} cuda_smoke={cuda_smoke_ok} "
        f"ffmpeg_smoke={ffmpeg_smoke_ok} missing_dependencies={len(missing)}"
    )
    return 0 if cuda_ok and cuda_smoke_ok and ffmpeg_smoke_ok and not missing else 2


if __name__ == "__main__":
    raise SystemExit(main())
