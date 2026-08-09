from __future__ import annotations

import argparse
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


PLUGIN_NAME = "astrbot_plugin_meguri_gateway"
PLUGIN_ROOT = Path(__file__).resolve().parent / PLUGIN_NAME
INCLUDED_SUFFIXES = {".py", ".json", ".yaml", ".txt", ".png", ".ttf", ".otf"}


def build_plugin_archive(output: Path) -> Path:
    """Create a deterministic standalone archive without local state or secrets."""

    resolved_output = output.resolve()
    resolved_output.parent.mkdir(parents=True, exist_ok=True)
    files = sorted(
        path
        for path in PLUGIN_ROOT.rglob("*")
        if path.is_file()
        and path.suffix in INCLUDED_SUFFIXES
        and "__pycache__" not in path.parts
    )
    required = {"main.py", "metadata.yaml", "_conf_schema.json", "requirements.txt"}
    missing = required.difference(path.name for path in files)
    if missing:
        raise RuntimeError(f"plugin package is missing: {', '.join(sorted(missing))}")

    with ZipFile(resolved_output, "w", compression=ZIP_DEFLATED) as archive:
        for path in files:
            relative = path.relative_to(PLUGIN_ROOT).as_posix()
            entry = ZipInfo(f"{PLUGIN_NAME}/{relative}")
            entry.date_time = (2026, 1, 1, 0, 0, 0)
            entry.compress_type = ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, path.read_bytes())
    return resolved_output


def main() -> int:
    parser = argparse.ArgumentParser(description="Package the standalone Meguri AstrBot plugin")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("output/astrbot_plugin_meguri_gateway.zip"),
    )
    args = parser.parse_args()
    print(build_plugin_archive(args.output))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
