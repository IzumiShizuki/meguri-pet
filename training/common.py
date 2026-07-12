from __future__ import annotations

import csv
import hashlib
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Sequence


PROJECT_ROOT = Path(r"D:\program\meguri-pet")
DATASET_ROOT = PROJECT_ROOT / "datasets" / "meguri"
REPORT_ROOT = PROJECT_ROOT / "reports"
CONFIG_ROOT = PROJECT_ROOT / "configs"
BASELINE_ROOT = PROJECT_ROOT / "baselines"
TRAINING_ROOT = PROJECT_ROOT / "training"
BUILD_ID = "meguri_v2_02c3db0c507d7c2d"

FFMPEG = Path(r"D:\environment\ffmpeg\bin\ffmpeg.exe")
FFPROBE = Path(r"D:\environment\ffmpeg\bin\ffprobe.exe")
GIT = Path(r"D:\environment\git\PortableGit\bin\git.exe")
GPT_SOVITS_ROOT = Path(r"D:\environment\projects\GPT-SoVITS")
GPT_SOVITS_PYTHON = Path(r"D:\environment\miniconda3\envs\GPTSoVits\python.exe")
PYTHON_314 = Path(r"D:\environment\anaconda3\envs\py314\python.exe")
MODEL_ROOT = Path(r"D:\AI\models\meguri")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def ensure_output_dirs() -> None:
    for path in (REPORT_ROOT, CONFIG_ROOT, BASELINE_ROOT, TRAINING_ROOT):
        path.mkdir(parents=True, exist_ok=True)


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8-sig") as handle:
        for line_number, line in enumerate(handle, start=1):
            if line.strip():
                try:
                    value = json.loads(line)
                except json.JSONDecodeError as exc:
                    raise ValueError(f"{path}:{line_number}: {exc}") from exc
                if not isinstance(value, dict):
                    raise ValueError(f"{path}:{line_number}: expected JSON object")
                rows.append(value)
    return rows


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
            count += 1
    return count


def read_delimited(path: Path, delimiter: str = ",") -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle, delimiter=delimiter))


def write_delimited(
    path: Path,
    rows: Iterable[dict[str, Any]],
    fieldnames: Sequence[str],
    delimiter: str = ",",
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=list(fieldnames),
            delimiter=delimiter,
            extrasaction="ignore",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(rows)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_command(
    args: Sequence[str | os.PathLike[str]],
    *,
    cwd: Path | None = None,
    timeout: int = 60,
) -> dict[str, Any]:
    command = [str(arg) for arg in args]
    try:
        completed = subprocess.run(
            command,
            cwd=str(cwd) if cwd else None,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
        return {
            "command": command,
            "returncode": completed.returncode,
            "stdout": completed.stdout.strip(),
            "stderr": completed.stderr.strip(),
        }
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {
            "command": command,
            "returncode": None,
            "stdout": "",
            "stderr": f"{type(exc).__name__}: {exc}",
        }


def path_is_within(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def file_tree_bytes(root: Path) -> int:
    if not root.exists():
        return 0
    return sum(path.stat().st_size for path in root.rglob("*") if path.is_file())


def collapse_text(value: str) -> str:
    return " ".join(str(value or "").replace("\r", "\n").split())

