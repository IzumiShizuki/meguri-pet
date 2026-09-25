from __future__ import annotations

import argparse
import ast
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts" / "adapter-protocol" / "v1"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--airi-root",
        type=Path,
        default=ROOT.parent / "airi-meguri",
    )
    args = parser.parse_args()
    schema = json.loads(
        (CONTRACT / "adapter-protocol.schema.json").read_text(encoding="utf-8")
    )
    flows = json.loads(
        (CONTRACT / "fixtures" / "flows.json").read_text(encoding="utf-8")
    )
    profiles = json.loads(
        (CONTRACT / "fixtures" / "profiles.json").read_text(encoding="utf-8")
    )

    dto = (ROOT / "packages" / "protocol" / "src" / "dto.ts").read_text(
        encoding="utf-8"
    )
    schema_runtime = (
        ROOT / "packages" / "protocol" / "src" / "schema.ts"
    ).read_text(encoding="utf-8")
    require(
        "adapter-protocol.schema.json" in schema_runtime,
        "root TypeScript DTO validator must import the canonical JSON Schema",
    )
    for definition in ("ClientProfile", "TurnStatus"):
        for value in schema["$defs"][definition]["enum"]:
            require(quoted(value, dto), f"root TypeScript DTO misses {definition}.{value}")
    for value in schema["$defs"]["ProtocolError"]["properties"]["code"]["enum"]:
        require(quoted(value, dto), f"root TypeScript ProtocolError misses {value}")

    client_path = (
        ROOT
        / "adapters"
        / "astrbot"
        / "astrbot_plugin_meguri_gateway"
        / "client.py"
    )
    known_events = python_frozenset(client_path, "_KNOWN_EVENT_TYPES")
    terminals = python_frozenset(client_path, "_TERMINAL_EVENT_TYPES")
    require(
        python_literal(client_path, "_CAPABILITIES")
        == profiles["astrbot"]["capabilities"],
        "AstrBot runtime capabilities differ from profiles.json",
    )
    require(
        python_literal(client_path, "_PERMISSIONS")
        == profiles["astrbot"]["permissions"],
        "AstrBot runtime permissions differ from profiles.json",
    )
    required_catalog = {item["type"] for item in flows["required_event_catalog"]}
    require(
        required_catalog <= known_events,
        f"AstrBot misses required events: {sorted(required_catalog - known_events)}",
    )
    root_event_source = (
        ROOT / "packages" / "protocol" / "src" / "turn-events.ts"
    ).read_text(encoding="utf-8")
    root_events = typescript_string_array(root_event_source, "turnEventTypes")
    require(
        known_events == root_events,
        "AstrBot and root TypeScript event catalogs differ: "
        f"python-only={sorted(known_events - root_events)}, "
        f"typescript-only={sorted(root_events - known_events)}",
    )
    require(
        terminals == set(flows["terminal"]),
        "AstrBot terminal event catalog differs from flows.json",
    )

    airi_protocol = (
        args.airi_root / "packages" / "meguri-airi-adapter" / "src" / "protocol.ts"
    )
    airi_fixture_test = airi_protocol.with_name("contract-fixture.test.ts")
    require(airi_protocol.is_file(), f"AIRI protocol DTO not found: {airi_protocol}")
    airi_text = airi_protocol.read_text(encoding="utf-8")
    airi_test_text = airi_fixture_test.read_text(encoding="utf-8")
    require(
        "contracts/adapter-protocol/v1/fixtures" in airi_test_text,
        "AIRI contract test must read meguri-pet canonical fixtures directly",
    )
    for definition in (
        "IdentityContext",
        "ClientCapabilities",
        "ClientPermissions",
        "HelloResponse",
        "EventEnvelope",
        "SessionSnapshot",
    ):
        required = schema["$defs"][definition]["required"]
        body = interface_body(airi_text, definition)
        for field in required:
            require(
                re.search(rf"\b{re.escape(field)}\??\s*:", body) is not None,
                f"AIRI {definition} misses schema field {field}",
            )
    for item in flows["required_event_catalog"]:
        require(
            quoted(item["type"], airi_text),
            f"AIRI event catalog misses {item['type']}",
        )

    print("adapter contract DTO/schema consistency: OK")
    return 0


def quoted(value: str, text: str) -> bool:
    return f"'{value}'" in text or f'"{value}"' in text


def interface_body(text: str, name: str) -> str:
    match = re.search(rf"export interface {re.escape(name)}\s*{{(.*?)\n}}", text, re.S)
    require(match is not None, f"AIRI interface {name} not found")
    return match.group(1)


def python_frozenset(path: Path, name: str) -> set[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        if not any(isinstance(target, ast.Name) and target.id == name for target in node.targets):
            continue
        if not isinstance(node.value, ast.Call) or not node.value.args:
            break
        value = ast.literal_eval(node.value.args[0])
        return set(value)
    raise AssertionError(f"Python constant {name} not found in {path}")


def python_literal(path: Path, name: str):
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        if any(isinstance(target, ast.Name) and target.id == name for target in node.targets):
            return ast.literal_eval(node.value)
    raise AssertionError(f"Python constant {name} not found in {path}")


def typescript_string_array(text: str, name: str) -> set[str]:
    match = re.search(
        rf"export const {re.escape(name)}\s*=\s*\[(.*?)\]\s*as const",
        text,
        re.S,
    )
    require(match is not None, f"TypeScript array {name} not found")
    return set(re.findall(r"['\"]([^'\"]+)['\"]", match.group(1)))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


if __name__ == "__main__":
    raise SystemExit(main())
