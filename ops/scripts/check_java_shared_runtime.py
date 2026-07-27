"""Read-only verification that AstrBot is backed by the remote Java Meguri Core."""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path
from typing import Any

import requests
import urllib3


urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)


def demux(raw: bytes) -> bytes:
    chunks: list[bytes] = []
    cursor = 0
    while cursor + 8 <= len(raw):
        length = struct.unpack(">I", raw[cursor + 4 : cursor + 8])[0]
        chunks.append(raw[cursor + 8 : cursor + 8 + length])
        cursor += 8 + length
    return b"".join(chunks)


class DockerApi:
    def __init__(self, base_url: str, cert_dir: Path) -> None:
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.cert = (str(cert_dir / "cert.pem"), str(cert_dir / "key.pem"))
        self.session.verify = False

    def container(self, name: str) -> dict[str, Any]:
        response = self.session.get(f"{self.base_url}/containers/{name}/json", timeout=15)
        response.raise_for_status()
        return response.json()

    def exec(self, container: str, command: list[str]) -> str:
        created = self.session.post(
            f"{self.base_url}/containers/{container}/exec",
            json={"AttachStdout": True, "AttachStderr": True, "Tty": False, "Cmd": command},
            timeout=15,
        )
        created.raise_for_status()
        exec_id = created.json()["Id"]
        started = self.session.post(
            f"{self.base_url}/exec/{exec_id}/start",
            json={"Detach": False, "Tty": False},
            timeout=30,
        )
        started.raise_for_status()
        inspected = self.session.get(f"{self.base_url}/exec/{exec_id}/json", timeout=15)
        inspected.raise_for_status()
        if inspected.json().get("ExitCode") != 0:
            raise RuntimeError(demux(started.content).decode("utf-8", "replace"))
        return demux(started.content).decode("utf-8", "replace")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker-url", default="https://111.228.35.186:2376")
    parser.add_argument("--cert-dir", type=Path, default=Path.home() / ".docker" / "cert_02")
    parser.add_argument("--core-container", default="meguri-staging-core-1")
    parser.add_argument("--astrbot-container", default="astrbot")
    parser.add_argument("--core-url", default="http://127.0.0.1:18080")
    args = parser.parse_args(argv)

    api = DockerApi(args.docker_url, args.cert_dir)
    failures: list[str] = []
    try:
        core = api.container(args.core_container)
        if core.get("State", {}).get("Status") != "running":
            failures.append("core container is not running")
        health: dict[str, Any] = {}
        try:
            health_raw = api.exec(
                args.astrbot_container,
                [
                    "python",
                    "-c",
                    "import urllib.request; "
                    "opener=urllib.request.build_opener(urllib.request.ProxyHandler({})); "
                    "print(opener.open(%r, timeout=5).read().decode())"
                    % (args.core_url + "/health"),
                ],
            )
            health = json.loads(health_raw.strip())
        except (ValueError, KeyError, requests.RequestException, RuntimeError) as error:
            failures.append(f"health endpoint is unavailable from AstrBot: {type(error).__name__}")
        if health:
            if health.get("runtime") != "java":
                failures.append("health.runtime is not java")
            if health.get("mode") != "configured-provider":
                failures.append("Java Core does not have a configured hosted provider")
            if health.get("memory_provider") != "PythonMemoryGateway":
                failures.append("Java Core is not configured with the Python memory bridge")
            if health.get("rag_provider") != "PythonRagGateway":
                failures.append("Java Core is not configured with the remote DashScope RAG bridge")
            if not str(health.get("build_id", "")).strip():
                failures.append("Java Core build_id is missing")
        print(json.dumps({"core_image": core.get("Config", {}).get("Image"), "health": health}, ensure_ascii=True))
    except (OSError, ValueError, KeyError, requests.RequestException, RuntimeError) as error:
        detail = str(error).strip().replace("\n", " ")
        failures.append(
            f"remote inspection failed: {type(error).__name__}"
            + (f": {detail[:400]}" if detail else "")
        )

    if failures:
        print(json.dumps({"status": "failed", "failures": failures}, ensure_ascii=True), file=sys.stderr)
        return 1
    print(json.dumps({"status": "passed"}, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
