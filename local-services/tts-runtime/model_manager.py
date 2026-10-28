"""Verified GPT-SoVITS profiles. Managed deletion never touches the installed voice."""

from __future__ import annotations

import hashlib
import ipaddress
import json
import shutil
import socket
import tempfile
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from urllib.parse import urlparse

import httpx

MAX_FILE_BYTES = 2 * 1024**3
MAX_PACKAGE_BYTES = 4 * 1024**3
MANIFEST_NAME = "voice-profile.json"
ROLES = {"gpt": {".ckpt"}, "sovits": {".pth"}, "referenceAudio": {".wav", ".ogg", ".mp3", ".flac"}}


def _relative(value: object) -> str:
    if not isinstance(value, str) or not value or "\\" in value or ":" in value:
        raise ValueError("语音资源必须使用包内相对路径。")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"..", ".", ""} for part in value.split("/")):
        raise ValueError("语音资源路径越界。")
    return value


def _file(root: Path, relative: str) -> Path:
    current = root
    for part in PurePosixPath(relative).parts:
        current /= part
        if current.is_symlink():
            raise ValueError("语音包不支持符号链接。")
    if not current.is_file() or current.stat().st_size == 0 or current.stat().st_size > MAX_FILE_BYTES:
        raise ValueError(f"语音资源缺失或超过 2 GB：{relative}")
    return current


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_safe_weights(path: Path) -> None:
    """Reject executable checkpoint objects before GPT-SoVITS' legacy torch loader."""
    try:
        import torch
    except ImportError as error:
        raise ValueError("语音运行时缺少 PyTorch，无法安全验证第三方权重。") from error
    if path.stat().st_size > MAX_FILE_BYTES:
        raise ValueError("权重文件超过安全加载限制。")
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            if sum(entry.file_size for entry in archive.infolist()) > MAX_FILE_BYTES:
                raise ValueError("权重解压数据超过 2 GB。")
    try:
        # weights_only prevents pickle globals from being executed. Never add an
        # arbitrary allowlist or retry with unsafe loading after a rejection.
        value = torch.load(path, map_location="cpu", weights_only=True)
    except Exception as error:
        raise ValueError("第三方权重无法通过安全加载验证；仅支持张量和基础数据，禁止可执行 pickle 对象。") from error
    nodes = 0
    tensor_bytes = 0

    def visit(item, depth=0):
        nonlocal nodes, tensor_bytes
        nodes += 1
        if depth > 40 or nodes > 500_000:
            raise ValueError("权重对象结构超过安全限制。")
        if isinstance(item, torch.Tensor):
            tensor_bytes += item.numel() * item.element_size()
            if tensor_bytes > MAX_FILE_BYTES:
                raise ValueError("权重张量超过 2 GB。")
        elif isinstance(item, dict):
            for key, child in item.items():
                if not isinstance(key, (str, int, float, bool)):
                    raise ValueError("权重映射键格式不安全。")
                visit(child, depth + 1)
        elif isinstance(item, (list, tuple)):
            for child in item:
                visit(child, depth + 1)
        elif item is not None and not isinstance(item, (str, bytes, int, float, bool)):
            raise ValueError("权重包含不支持的对象。")

    if not isinstance(value, dict):
        raise ValueError("GPT-SoVITS 权重必须是包含张量的映射。")
    visit(value)


def validate_manifest(value: object) -> dict:
    if not isinstance(value, dict) or value.get("schemaVersion") != 1:
        raise ValueError("语音包需要 schemaVersion=1 的 voice-profile.json。")
    if value.get("engine") != "gpt-sovits":
        raise ValueError("此运行时只支持 GPT-SoVITS profile；VITS/ONNX 模型不能在此运行。")
    if value.get("modelVersion") not in {"v1", "v2", "v2Pro", "v2ProPlus", "v3", "v4"}:
        raise ValueError("语音包声明了不支持的 GPT-SoVITS 版本。")
    if not isinstance(value.get("name"), str) or not 1 <= len(value["name"]) <= 100:
        raise ValueError("语音包名称必须为 1–100 字。")
    if value.get("language") not in {"ja", "zh", "en", "ko", "yue"}:
        raise ValueError("语音参考语言无效。")
    if not isinstance(value.get("referenceText"), str) or not 1 <= len(value["referenceText"]) <= 1000:
        raise ValueError("必须提供与参考音频匹配的 referenceText。")
    files = value.get("files")
    if not isinstance(files, dict) or set(files) != set(ROLES):
        raise ValueError("语音包需要 gpt、sovits、referenceAudio 三项资源。")
    names = set()
    for role, suffixes in ROLES.items():
        entry = files[role]
        if not isinstance(entry, dict):
            raise ValueError("语音文件声明无效。")
        relative = _relative(entry.get("path"))
        if PurePosixPath(relative).suffix.lower() not in suffixes:
            raise ValueError(f"{role} 资源格式不兼容；不能使用 ONNX 代替 GPT-SoVITS 权重。")
        if relative.lower() in names:
            raise ValueError("语音包资源路径重复。")
        names.add(relative.lower())
        digest = entry.get("sha256")
        if not isinstance(digest, str) or len(digest) != 64 or any(char not in "0123456789abcdef" for char in digest):
            raise ValueError("每个语音资源必须提供小写 SHA-256 校验值。")
    return value


def validate_package(root: Path) -> dict:
    root = root.absolute()
    if root.is_symlink() or not root.is_dir():
        raise ValueError("请选择语音包目录。")
    manifest = _file(root, MANIFEST_NAME)
    if manifest.stat().st_size > 64 * 1024:
        raise ValueError("语音包说明超过 64 KB。")
    value = validate_manifest(json.loads(manifest.read_text(encoding="utf-8")))
    total = 0
    for entry in value["files"].values():
        path = _file(root, entry["path"])
        total += path.stat().st_size
        if total > MAX_PACKAGE_BYTES:
            raise ValueError("语音包超过 4 GB。")
        if sha256_file(path) != entry["sha256"]:
            raise ValueError(f"SHA-256 校验失败：{entry['path']}")
    return value


def _https(url: str) -> str:
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.port not in {None, 443}:
        raise ValueError("请使用无凭据的 HTTPS 资源地址（443 端口）。")
    addresses = socket.getaddrinfo(parsed.hostname, 443, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(address[4][0]).is_global for address in addresses):
        raise ValueError("下载资源必须来自公共 HTTPS 站点。")
    return url


class VoiceModelManager:
    def __init__(self, root: Path, default_profile: dict):
        self.root = root.absolute()
        self.default = default_profile
        self.root.mkdir(parents=True, exist_ok=True)
        self.catalog_file = self.root / "catalog.json"

    def _read(self) -> dict:
        if not self.catalog_file.exists():
            return {"activeId": "default", "profiles": []}
        return json.loads(self.catalog_file.read_text(encoding="utf-8"))

    def _save(self, catalog: dict) -> None:
        temporary = self.catalog_file.with_suffix(f".{uuid.uuid4()}.tmp")
        temporary.write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(self.catalog_file)

    def profile(self, profile_id: str) -> dict:
        if profile_id == "default":
            return self.default
        try:
            uuid.UUID(profile_id)
        except (ValueError, TypeError) as error:
            raise ValueError("语音模型标识无效。") from error
        if not any(entry["id"] == profile_id for entry in self._read()["profiles"]):
            raise ValueError("未找到此语音模型。")
        root = self.root / profile_id
        manifest = validate_package(root)
        return {**manifest, "root": root, "id": profile_id}

    def catalog(self, ready: bool) -> dict:
        catalog = self._read()
        profiles = [{"id": "default", "name": self.default["name"], "engine": "gpt-sovits", "modelVersion": self.default["modelVersion"], "language": self.default["language"], "readOnly": True, "active": catalog["activeId"] == "default", "available": all(Path(path).is_file() for path in self.default["resolvedFiles"].values())}]
        for entry in catalog["profiles"]:
            available = True
            try:
                # Listing checks presence; activation performs complete SHA-256 verification.
                for file in entry["files"].values():
                    _file(self.root / entry["id"], file["path"])
            except ValueError:
                available = False
            profiles.append({"id": entry["id"], "name": entry["name"], "engine": "gpt-sovits", "modelVersion": entry["modelVersion"], "language": entry["language"], "importedAt": entry["importedAt"], "readOnly": False, "active": catalog["activeId"] == entry["id"], "available": available})
        return {"engine": "GPT-SoVITS", "ready": ready, "activeId": catalog["activeId"], "profiles": profiles}

    def import_package(self, source: Path) -> str:
        manifest = validate_package(source)
        profile_id = str(uuid.uuid4())
        target = self.root / profile_id
        target.mkdir()
        try:
            for file in manifest["files"].values():
                destination = target / file["path"]
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(_file(source, file["path"]), destination)
            (target / MANIFEST_NAME).write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
            validate_package(target)
            catalog = self._read()
            catalog["profiles"].append({**manifest, "id": profile_id, "importedAt": datetime.now(timezone.utc).isoformat()})
            self._save(catalog)
            return profile_id
        except Exception:
            shutil.rmtree(target)
            raise

    def set_active(self, profile_id: str) -> None:
        self.profile(profile_id)
        catalog = self._read()
        catalog["activeId"] = profile_id
        self._save(catalog)

    def prepare_activation(self, profile_id: str) -> dict:
        profile = self.profile(profile_id)
        if profile_id != "default":
            for role in ("gpt", "sovits"):
                verify_safe_weights(profile["root"] / profile["files"][role]["path"])
        return profile

    def remove(self, profile_id: str) -> None:
        if profile_id == "default":
            raise ValueError("预装语音为只读资源，不能删除。")
        self.profile(profile_id)
        catalog = self._read()
        if catalog["activeId"] == profile_id:
            raise ValueError("请先切换到其他语音，再移除当前语音。")
        target = (self.root / profile_id).resolve()
        if target.parent != self.root.resolve():
            raise ValueError("语音删除路径越界。")
        shutil.rmtree(target)
        catalog["profiles"] = [entry for entry in catalog["profiles"] if entry["id"] != profile_id]
        self._save(catalog)

    async def download(self, manifest_url: str) -> str:
        async with httpx.AsyncClient(timeout=60, follow_redirects=False, trust_env=False) as client:
            async with client.stream("GET", _https(manifest_url)) as response:
                response.raise_for_status()
                raw = bytearray()
                async for block in response.aiter_bytes():
                    raw.extend(block)
                    if len(raw) > 64 * 1024:
                        raise ValueError("下载说明超过 64 KB。")
            manifest = validate_manifest(json.loads(raw))
            with tempfile.TemporaryDirectory(prefix="download-", dir=self.root) as temporary:
                root = Path(temporary)
                total = 0
                for entry in manifest["files"].values():
                    url = entry.get("url")
                    if not isinstance(url, str):
                        raise ValueError("下载说明必须为每个文件指定 HTTPS url。")
                    target = root / entry["path"]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    size = 0
                    async with client.stream("GET", _https(url)) as response:
                        response.raise_for_status()
                        with target.open("wb") as stream:
                            async for block in response.aiter_bytes():
                                size += len(block)
                                total += len(block)
                                if size > MAX_FILE_BYTES or total > MAX_PACKAGE_BYTES:
                                    raise ValueError("下载语音超过资源大小限制。")
                                stream.write(block)
                (root / MANIFEST_NAME).write_text(json.dumps(manifest), encoding="utf-8")
                return self.import_package(root)
