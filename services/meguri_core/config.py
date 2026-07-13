from __future__ import annotations

import json
import os
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONFIG_ROOT = ROOT / "configs"
DATA_ROOT = Path(os.getenv("MEGURI_DATA_ROOT", ROOT / "datasets" / "meguri"))
BUILD_REPORT = DATA_ROOT / "build_report.json"
SYSTEM_PROMPT_PATH = CONFIG_ROOT / "meguri_system_prompt.txt"
RESPONSE_SCHEMA_PATH = CONFIG_ROOT / "meguri_response.schema.json"


def load_build_id() -> str:
    try:
        return str(json.loads(BUILD_REPORT.read_text(encoding="utf-8"))["build_id"])
    except (FileNotFoundError, KeyError, json.JSONDecodeError):
        return os.getenv("MEGURI_BUILD_ID", "meguri_local_mock")


BUILD_ID = load_build_id()
CHARACTER_ID = "meguri"
DEFAULT_TIMEZONE = "Asia/Shanghai"
