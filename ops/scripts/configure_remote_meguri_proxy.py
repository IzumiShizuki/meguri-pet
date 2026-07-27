"""Install the token-protected public Meguri Core route in remote OpenResty."""

from __future__ import annotations

import argparse
import io
import json
import struct
import tarfile
import time
from pathlib import Path

import requests
import urllib3


urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)


class DockerApi:
    def __init__(self, base_url: str, cert_dir: Path) -> None:
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.cert = (str(cert_dir / "cert.pem"), str(cert_dir / "key.pem"))
        self.session.verify = False

    def call(self, method: str, path: str, *, ok: tuple[int, ...] = (200, 201, 204), **kwargs):
        response = self.session.request(method, self.base_url + path, timeout=60, **kwargs)
        if response.status_code not in ok:
            raise RuntimeError(f"{method} {path} failed with HTTP {response.status_code}")
        return response

    def read_archive(self, container: str, path: str) -> bytes:
        response = self.call("GET", f"/containers/{container}/archive", params={"path": path})
        with tarfile.open(fileobj=io.BytesIO(response.content), mode="r:*") as archive:
            member = next(item for item in archive.getmembers() if item.isfile())
            extracted = archive.extractfile(member)
            if extracted is None:
                raise RuntimeError("OpenResty configuration archive is empty")
            return extracted.read()

    def write_archive(self, container: str, directory: str, files: dict[str, bytes]) -> None:
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w") as archive:
            for name, data in files.items():
                info = tarfile.TarInfo(name)
                info.size = len(data)
                info.mode = 0o644
                info.mtime = int(time.time())
                archive.addfile(info, io.BytesIO(data))
        buffer.seek(0)
        self.call(
            "PUT",
            f"/containers/{container}/archive",
            params={"path": directory},
            data=buffer,
            headers={"Content-Type": "application/x-tar"},
        )

    def exec(self, container: str, command: list[str]) -> tuple[int, str]:
        created = self.call(
            "POST",
            f"/containers/{container}/exec",
            json={"AttachStdout": True, "AttachStderr": True, "Tty": False, "Cmd": command},
        )
        exec_id = created.json()["Id"]
        started = self.call(
            "POST", f"/exec/{exec_id}/start", json={"Detach": False, "Tty": False}
        )
        inspected = self.call("GET", f"/exec/{exec_id}/json").json()
        return int(inspected.get("ExitCode") or 0), demux(started.content).decode("utf-8", "replace")


def demux(raw: bytes) -> bytes:
    chunks: list[bytes] = []
    cursor = 0
    while cursor + 8 <= len(raw):
        length = struct.unpack(">I", raw[cursor + 4 : cursor + 8])[0]
        chunks.append(raw[cursor + 8 : cursor + 8 + length])
        cursor += 8 + length
    return b"".join(chunks)


def insert_route(config: str, token: str) -> str:
    if "/meguri-core/" in config:
        raise RuntimeError("Meguri Core route already exists")
    server_name = "server_name bot.shizuki.online"
    positions: list[int] = []
    cursor = 0
    while (position := config.find(server_name, cursor)) >= 0:
        positions.append(position)
        cursor = position + len(server_name)
    if not positions:
        raise RuntimeError("bot.shizuki.online server block was not found")
    position = -1
    block_start = -1
    for candidate in positions:
        candidate_start = config.rfind("server {", 0, candidate)
        if candidate_start >= 0 and "listen 443 ssl" in config[candidate_start:candidate]:
            position = candidate
            block_start = candidate_start
            break
    if position < 0 or block_start < 0:
        raise RuntimeError("bot.shizuki.online HTTPS server block was not found")
    depth = 0
    block_end = None
    for index in range(block_start, len(config)):
        if config[index] == "{":
            depth += 1
        elif config[index] == "}":
            depth -= 1
            if depth == 0:
                block_end = index + 1
                break
    if block_end is None:
        raise RuntimeError("OpenResty server block end was not found")
    fallback = config.find("location /", position, block_end)
    if fallback < 0:
        raise RuntimeError("bot.shizuki.online fallback location was not found")
    indent = "    "
    route = (
        f"\n{indent}location ^~ /meguri-core/ {{\n"
        f"{indent}    set $meguri_core_allowed 0;\n"
        f'{indent}    if ($http_authorization = "Bearer {token}") {{ set $meguri_core_allowed 1; }}\n'
        f"{indent}    if ($request_method = OPTIONS) {{ set $meguri_core_allowed 1; }}\n"
        f"{indent}    if ($meguri_core_allowed = 0) {{ return 401; }}\n\n"
        f"{indent}    proxy_pass http://127.0.0.1:18080/;\n"
        f"{indent}    proxy_http_version 1.1;\n"
        f"{indent}    proxy_set_header Host $host;\n"
        f"{indent}    proxy_set_header X-Real-IP $remote_addr;\n"
        f"{indent}    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
        f"{indent}    proxy_set_header X-Forwarded-Proto $scheme;\n"
        f"{indent}    proxy_buffering off;\n"
        f"{indent}    proxy_read_timeout 180s;\n"
        f"{indent}}}\n{indent}"
    )
    return config[:fallback] + route + config[fallback:]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker-url", default="https://111.228.35.186:2376")
    parser.add_argument("--cert-dir", type=Path, default=Path.home() / ".docker" / "cert_02")
    parser.add_argument("--container", default="openresty")
    parser.add_argument(
        "--token-file",
        type=Path,
        default=Path(r"D:\environment\secrets\meguri\desktop-core-token.txt"),
    )
    parser.add_argument(
        "--config-path",
        default="/usr/local/openresty/nginx/conf/conf.d/10-shizuki-migration.conf",
    )
    args = parser.parse_args()

    token = args.token_file.read_text(encoding="utf-8").strip()
    if len(token) != 64:
        raise RuntimeError("Desktop Core token must contain exactly 64 characters")
    config_path = Path(args.config_path)
    config_dir = str(config_path.parent).replace("\\", "/")
    config_name = config_path.name
    backup_name = config_name + ".bak-java-shared-20260725-1805"
    api = DockerApi(args.docker_url, args.cert_dir)
    original = api.read_archive(args.container, args.config_path)
    patched = insert_route(original.decode("utf-8"), token).encode("utf-8")
    api.write_archive(args.container, config_dir, {config_name: patched, backup_name: original})
    code, output = api.exec(args.container, ["/usr/local/openresty/bin/openresty", "-t"])
    if code != 0:
        api.write_archive(args.container, config_dir, {config_name: original})
        api.exec(args.container, ["/usr/local/openresty/bin/openresty", "-t"])
        raise RuntimeError("OpenResty rejected the Meguri route: " + output[-800:])
    code, _ = api.exec(args.container, ["/usr/local/openresty/bin/openresty", "-s", "reload"])
    if code != 0:
        api.write_archive(args.container, config_dir, {config_name: original})
        api.exec(args.container, ["/usr/local/openresty/bin/openresty", "-s", "reload"])
        raise RuntimeError("OpenResty reload failed and the prior configuration was restored")
    print(
        json.dumps(
            {
                "status": "configured",
                "config_path": args.config_path,
                "backup_path": config_dir + "/" + backup_name,
                "nginx_test": "passed",
                "reloaded": True,
            },
            ensure_ascii=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
