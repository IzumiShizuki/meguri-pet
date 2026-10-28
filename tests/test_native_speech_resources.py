"""Voice lifecycle, integrity and host authorization without touching installed weights."""
import hashlib
import importlib
import json
from pathlib import Path

import httpx
import pytest

manager_module = importlib.import_module("local-services.tts-runtime.model_manager")
server = importlib.import_module("local-services.tts-runtime.server")


def profile_fixture(root: Path) -> Path:
    root.mkdir()
    files = {}
    for role, name in {"gpt": "test.ckpt", "sovits": "test.pth", "referenceAudio": "reference.wav"}.items():
        data = f"isolated fixture {role}".encode()
        (root / name).write_bytes(data)
        files[role] = {"path": name, "sha256": hashlib.sha256(data).hexdigest()}
    (root / "voice-profile.json").write_text(json.dumps({"schemaVersion": 1, "engine": "gpt-sovits", "name": "Test", "modelVersion": "v2Pro", "language": "ja", "referenceText": "こんにちは。", "files": files}))
    return root


def manager_fixture(root: Path):
    return manager_module.VoiceModelManager(root, {"id": "default", "name": "Installed", "modelVersion": "v2Pro", "language": "ja", "referenceText": "existing", "resolvedFiles": {"gpt": "/unchanged/gpt.ckpt", "sovits": "/unchanged/sovits.pth", "referenceAudio": "/unchanged/reference.wav"}})


def test_import_verifies_hashes_and_only_removes_managed_resources(tmp_path):
    source = profile_fixture(tmp_path / "source")
    manager = manager_fixture(tmp_path / "managed")
    profile_id = manager.import_package(source)
    assert manager.profile(profile_id)["referenceText"] == "こんにちは。"
    manager.set_active(profile_id)
    with pytest.raises(ValueError, match="切换"):
        manager.remove(profile_id)
    with pytest.raises(ValueError, match="只读"):
        manager.remove("default")
    manager.set_active("default")
    manager.remove(profile_id)
    assert (source / "test.ckpt").is_file()
    assert manager.catalog(True)["activeId"] == "default"
    (source / "test.ckpt").write_text("tampered")
    with pytest.raises(ValueError, match="SHA-256"):
        manager.import_package(source)
    assert len(manager.catalog(True)["profiles"]) == 1


def test_rejects_onnx_traversal_and_unverified_packages(tmp_path):
    source = profile_fixture(tmp_path / "source")
    path = source / "voice-profile.json"
    value = json.loads(path.read_text())
    value["engine"] = "vits-onnx"
    with pytest.raises(ValueError, match="VITS/ONNX"):
        manager_module.validate_manifest(value)
    value["engine"] = "gpt-sovits"
    value["files"]["gpt"]["path"] = "../outside.ckpt"
    with pytest.raises(ValueError, match="越界"):
        manager_module.validate_manifest(value)


@pytest.mark.asyncio
async def test_model_endpoints_require_host_token_and_restore_failed_activation(tmp_path, monkeypatch):
    manager = manager_fixture(tmp_path / "managed")
    profile_id = manager.import_package(profile_fixture(tmp_path / "source"))
    monkeypatch.setattr(server.state, "manager", manager)
    monkeypatch.setattr(server.state, "token", "test-secret-local-host-token")
    monkeypatch.setattr(server.state, "ready", True)
    monkeypatch.setattr(server.state, "active_profile", manager.default)
    # This case isolates upstream rollback; real tensor safety is exercised by
    # the dedicated GPTSoVits-environment checkpoint test.
    monkeypatch.setattr(manager, "prepare_activation", manager.profile)
    calls = []

    async def switch(profile):
        calls.append(profile["id"])
        if profile["id"] == profile_id:
            raise RuntimeError("simulated upstream failure")

    monkeypatch.setattr(server, "switch_weights", switch)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=server.app), base_url="http://127.0.0.1:9880") as client:
        assert (await client.get("/tts/models")).status_code == 401
        assert (await client.post("/tts/models/import", json={"directory": "D:/private"})).status_code == 401
        headers = {"Authorization": "Bearer test-secret-local-host-token"}
        listing = await client.get("/tts/models", headers=headers)
        assert listing.status_code == 200
        assert "test-secret" not in listing.text
        response = await client.post(f"/tts/models/{profile_id}/activate", headers=headers)
        assert response.status_code == 502
        assert calls == [profile_id, "default"]
        assert manager.catalog(True)["activeId"] == "default"
        assert server.state.active_profile["id"] == "default"


def test_speed_is_bounded_and_compatible_with_existing_clients():
    request = server.SynthesisRequest(text="test", request_id="test-request")
    assert request.speed_factor == 1
    with pytest.raises(ValueError):
        server.SynthesisRequest(text="test", request_id="test", speed_factor=2.1)


@pytest.mark.asyncio
async def test_download_verifies_integrity_and_preserves_active_voice(tmp_path, monkeypatch):
    source = profile_fixture(tmp_path / "source")
    manifest = json.loads((source / "voice-profile.json").read_text())
    resources = {}
    for entry in manifest["files"].values():
        entry["url"] = f"https://voice.example/{entry['path']}"
        resources[entry["url"]] = (source / entry["path"]).read_bytes()
    corrupt = {"value": True}

    def transport(request):
        if request.url.path == "/voice-profile.json":
            return httpx.Response(200, json=manifest)
        data = resources[str(request.url)]
        if corrupt["value"] and request.url.path.endswith(".ckpt"):
            data = b"corrupted download"
        return httpx.Response(200, content=data)

    real_client = httpx.AsyncClient
    monkeypatch.setattr(manager_module.httpx, "AsyncClient", lambda **kwargs: real_client(transport=httpx.MockTransport(transport), **kwargs))
    monkeypatch.setattr(manager_module.socket, "getaddrinfo", lambda *_args, **_kwargs: [(2, 1, 6, "", ("93.184.216.34", 443))])
    manager = manager_fixture(tmp_path / "managed")
    with pytest.raises(ValueError, match="SHA-256"):
        await manager.download("https://voice.example/voice-profile.json")
    assert manager.catalog(True)["activeId"] == "default"
    assert len(manager.catalog(True)["profiles"]) == 1
    corrupt["value"] = False
    imported = await manager.download("https://voice.example/voice-profile.json")
    assert manager.profile(imported)["name"] == "Test"
    assert manager.catalog(True)["activeId"] == "default"


def test_download_rejects_credentials_private_addresses_and_non_https(monkeypatch):
    for url in ("http://example.com/model", "https://user:password@example.com/model", "https://example.com:8443/model"):
        with pytest.raises(ValueError, match="HTTPS"):
            manager_module._https(url)
    monkeypatch.setattr(manager_module.socket, "getaddrinfo", lambda *_args, **_kwargs: [(2, 1, 6, "", ("127.0.0.1", 443))])
    with pytest.raises(ValueError, match="公共"):
        manager_module._https("https://private.example/model")
