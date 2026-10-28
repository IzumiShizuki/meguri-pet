"""Loopback bridge from AIRI's speech provider to the local GPT-SoVITS v2Pro API.

The bridge owns the local model process and exposes only the repository's
``configs/local_tts_contract.json``. It never binds beyond 127.0.0.1.
"""

from __future__ import annotations

import asyncio
import os
import signal
import subprocess
import secrets
import hmac
from contextlib import asynccontextmanager
from pathlib import Path
from typing import AsyncIterator

import httpx
import uvicorn
from fastapi import FastAPI, HTTPException, Header, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel, Field
import yaml

from .model_manager import VoiceModelManager

ROOT = Path(__file__).resolve().parents[2]
GPT_ROOT = Path(os.getenv("MEGURI_TTS_FRAMEWORK_ROOT", r"D:\environment\projects\GPT-SoVITS"))
PYTHON = Path(os.getenv("MEGURI_TTS_PYTHON", r"D:\environment\miniconda3\envs\GPTSoVits\python.exe"))
CONFIG = Path(os.getenv(
    "MEGURI_TTS_CONFIG",
    str(ROOT / "configs" / "tts_infer_v2pro_full_ja_extended_denoised.yaml"),
))
MODEL_VERSION = os.getenv("MEGURI_TTS_MODEL_VERSION", "meguri_v2_02c3db0c507d7c2d-baseline_001-e4")
REF_AUDIO = Path(os.getenv(
    "MEGURI_TTS_REF_AUDIO",
    str(ROOT / "data" / "meguri" / "assets" / "voice_safe" / "MGR000238.ogg"),
))
REF_TEXT = os.getenv(
    "MEGURI_TTS_REF_TEXT",
    "叶先輩に告白したにも関わらず、この『運命的恋愛メーター』が黒い……",
)
UPSTREAM_PORT = int(os.getenv("MEGURI_TTS_UPSTREAM_PORT", "9881"))
UPSTREAM = f"http://127.0.0.1:{UPSTREAM_PORT}"
TOKEN_FILE = Path(os.getenv("MEGURI_TTS_MANAGEMENT_TOKEN_FILE", "D:/environment/secrets/meguri/tts-management-token.txt"))
MODELS_ROOT = Path(os.getenv("MEGURI_TTS_MODELS_ROOT", str(ROOT / ".codex-runtime" / "voice-profiles")))


def management_token() -> str:
    """Create a shared local-host credential once; never expose it in health/catalog."""
    if not TOKEN_FILE.is_absolute() or TOKEN_FILE.resolve().is_relative_to(ROOT.resolve()):
        raise RuntimeError("local TTS management credential must use an absolute file outside the repository")
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    try:
        with TOKEN_FILE.open("x", encoding="utf-8") as stream:
            stream.write(secrets.token_urlsafe(48))
        TOKEN_FILE.chmod(0o600)
    except FileExistsError:
        pass
    token = TOKEN_FILE.read_text(encoding="utf-8").strip()
    if len(token) < 32:
        raise RuntimeError("local TTS management credential is invalid")
    return token


def default_voice_profile() -> dict:
    configuration = yaml.safe_load(CONFIG.read_text(encoding="utf-8")) if CONFIG.is_file() else {}
    custom = configuration.get("custom", {})
    return {"id": "default", "name": "Meguri 预装语音", "modelVersion": custom.get("version", "v2Pro"), "language": "ja", "referenceText": REF_TEXT,
            "resolvedFiles": {"gpt": custom.get("t2s_weights_path", ""), "sovits": custom.get("vits_weights_path", ""), "referenceAudio": str(REF_AUDIO)}}


class SynthesisRequest(BaseModel):
    text: str = Field(min_length=1, max_length=1000)
    voice_style: str = "neutral"
    expression_intensity: str = "medium"
    request_id: str = Field(min_length=1, max_length=160)
    speed_factor: float = Field(default=1.0, ge=0.5, le=2.0)


class ImportRequest(BaseModel):
    directory: str = Field(min_length=1, max_length=2048)


class DownloadRequest(BaseModel):
    manifest_url: str = Field(min_length=1, max_length=2048)


class RuntimeState:
    process: subprocess.Popen[bytes] | None = None
    ready: bool = False
    manager: VoiceModelManager | None = None
    active_profile: dict | None = None
    token: str = ""
    lock: asyncio.Lock

    def __init__(self) -> None:
        self.lock = asyncio.Lock()


state = RuntimeState()


def style_parameters(style: str, intensity: str) -> tuple[float, float, float]:
    """Map semantic cues to bounded GPT-SoVITS controls."""
    speed = {
        "sleepy": 0.88,
        "soft": 0.94,
        "restrained": 0.96,
        "worried": 0.95,
        "cheerful": 1.06,
        "teasing": 1.03,
        "affectionate": 0.98,
        "neutral": 1.0,
    }.get(style, 1.0)
    temperature = {"low": 0.72, "medium": 0.82, "high": 0.92}.get(intensity, 0.82)
    top_p = {"low": 0.84, "medium": 0.92, "high": 0.98}.get(intensity, 0.92)
    return speed, temperature, top_p


def language_for(text: str) -> str:
    if any("\u3040" <= char <= "\u30ff" for char in text):
        return "ja"
    if any("\u4e00" <= char <= "\u9fff" for char in text):
        return "zh"
    return "en"


async def wait_upstream() -> None:
    deadline = asyncio.get_running_loop().time() + 180
    async with httpx.AsyncClient(timeout=3) as client:
        while asyncio.get_running_loop().time() < deadline:
            if state.process is not None and state.process.poll() is not None:
                raise RuntimeError(f"GPT-SoVITS exited with code {state.process.returncode}")
            try:
                response = await client.get(
                    f"{UPSTREAM}/set_refer_audio",
                    params={"refer_audio_path": str(REF_AUDIO)},
                )
                if response.status_code == 200:
                    state.ready = True
                    return
            except httpx.HTTPError:
                pass
            await asyncio.sleep(1)
    raise TimeoutError("GPT-SoVITS did not become ready within 180 seconds")


def start_upstream() -> subprocess.Popen[bytes]:
    if not PYTHON.is_file():
        raise FileNotFoundError(f"TTS Python environment not found: {PYTHON}")
    if not CONFIG.is_file():
        raise FileNotFoundError(f"TTS config not found: {CONFIG}")
    if not REF_AUDIO.is_file():
        raise FileNotFoundError(f"TTS reference audio not found: {REF_AUDIO}")
    GPT_ROOT.mkdir(parents=True, exist_ok=True)
    log_dir = Path(os.getenv("MEGURI_TTS_LOG_DIR", str(ROOT / "log")))
    log_dir.mkdir(parents=True, exist_ok=True)
    log = (log_dir / "gpt-sovits-api-v2.log").open("ab")
    return subprocess.Popen(
        [str(PYTHON), "api_v2.py", "-a", "127.0.0.1", "-p", str(UPSTREAM_PORT), "-c", str(CONFIG)],
        cwd=GPT_ROOT,
        stdout=log,
        stderr=subprocess.STDOUT,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )


async def stop_upstream() -> None:
    process = state.process
    state.ready = False
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        await asyncio.to_thread(process.wait, 10)
    except subprocess.TimeoutExpired:
        process.kill()
        await asyncio.to_thread(process.wait, 5)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    state.token = management_token()
    state.manager = VoiceModelManager(MODELS_ROOT, default_voice_profile())
    state.active_profile = state.manager.default
    state.process = start_upstream()
    try:
        await wait_upstream()
        desired_id = state.manager.catalog(state.ready)["activeId"]
        if desired_id != "default":
            try:
                await activate_profile(desired_id)
            except Exception:
                state.manager.set_active("default")
        yield
    finally:
        await stop_upstream()


app = FastAPI(title="Meguri Local TTS", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["null"],
    allow_origin_regex=r"^http://(?:127\.0\.0\.1|localhost):\d+$",
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Content-Type"],
)


@app.get("/health")
async def health() -> dict[str, object]:
    profile = state.active_profile or {}
    version = MODEL_VERSION if profile.get("id", "default") == "default" else f"{profile.get('modelVersion', 'unknown')}:{profile['id']}"
    return {"status": "ok" if state.ready else "starting", "model_version": version, "ready": state.ready}


def require_host(authorization: str | None = Header(default=None)) -> None:
    expected = f"Bearer {state.token}"
    if not state.token or not authorization or not hmac.compare_digest(authorization, expected):
        raise HTTPException(status_code=401, detail="local desktop host authorization required")


def manager() -> VoiceModelManager:
    if state.manager is None:
        raise HTTPException(status_code=503, detail="local voice manager is not ready")
    return state.manager


def resolved_profile_files(profile: dict) -> dict[str, str]:
    if profile["id"] == "default":
        return profile["resolvedFiles"]
    return {role: str(profile["root"] / entry["path"]) for role, entry in profile["files"].items()}


async def switch_weights(profile: dict) -> None:
    files = resolved_profile_files(profile)
    async with httpx.AsyncClient(timeout=120, trust_env=False) as client:
        for endpoint, parameter in [("set_gpt_weights", "gpt"), ("set_sovits_weights", "sovits")]:
            response = await client.get(f"{UPSTREAM}/{endpoint}", params={"weights_path": files[parameter]})
            response.raise_for_status()
        response = await client.get(f"{UPSTREAM}/set_refer_audio", params={"refer_audio_path": files["referenceAudio"]})
        response.raise_for_status()


async def activate_profile(profile_id: str) -> None:
    selected = await asyncio.to_thread(manager().prepare_activation, profile_id)
    previous = state.active_profile or manager().default
    try:
        await switch_weights(selected)
        await asyncio.to_thread(manager().set_active, profile_id)
    except Exception as error:
        try:
            await switch_weights(previous)
        except Exception:
            state.ready = False
        raise HTTPException(status_code=502, detail="语音模型激活失败；已尝试恢复原语音。") from error
    state.active_profile = selected
    state.ready = True


@app.get("/tts/models", dependencies=[Depends(require_host)])
async def list_models() -> dict:
    return await asyncio.to_thread(manager().catalog, state.ready)


@app.post("/tts/models/import", dependencies=[Depends(require_host)])
async def import_model(request: ImportRequest) -> dict:
    async with state.lock:
        try:
            await asyncio.to_thread(manager().import_package, Path(request.directory))
        except (ValueError, OSError) as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
    return {"ok": True, "catalog": manager().catalog(state.ready)}


@app.post("/tts/models/download", dependencies=[Depends(require_host)])
async def download_model(request: DownloadRequest) -> dict:
    async with state.lock:
        try:
            await manager().download(request.manifest_url)
        except (ValueError, OSError, httpx.HTTPError) as error:
            raise HTTPException(status_code=400, detail="语音资源下载或校验失败；请检查 HTTPS 清单与 SHA-256。") from error
    return {"ok": True, "catalog": manager().catalog(state.ready)}


@app.post("/tts/models/{profile_id}/activate", dependencies=[Depends(require_host)])
async def activate_model(profile_id: str) -> dict:
    async with state.lock:
        try:
            await activate_profile(profile_id)
        except (ValueError, OSError) as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
    return {"ok": True, "catalog": manager().catalog(state.ready)}


@app.delete("/tts/models/{profile_id}", dependencies=[Depends(require_host)])
async def remove_model(profile_id: str) -> dict:
    async with state.lock:
        try:
            await asyncio.to_thread(manager().remove, profile_id)
        except (ValueError, OSError) as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
    return {"ok": True, "catalog": manager().catalog(state.ready)}


@app.post("/tts/synthesize")
async def synthesize(request: SynthesisRequest) -> Response:
    try:
        async with state.lock:
            if not state.ready:
                raise HTTPException(status_code=503, detail="local TTS is not ready")
            # Capture the profile under the same lock as activation and inference.
            # Otherwise queued speech can pair old reference audio with new weights.
            speed, temperature, top_p = style_parameters(request.voice_style, request.expression_intensity)
            profile = state.active_profile or default_voice_profile()
            files = resolved_profile_files(profile)
            payload = {
                "text": request.text,
                "text_lang": language_for(request.text),
                "ref_audio_path": files["referenceAudio"],
                "prompt_lang": profile["language"],
                "prompt_text": profile["referenceText"],
                "text_split_method": "cut5",
                "media_type": "wav",
                "streaming_mode": False,
                "speed_factor": max(0.5, min(2.0, speed * request.speed_factor)),
                "temperature": temperature,
                "top_p": top_p,
                "batch_size": 1,
            }
            async with httpx.AsyncClient(timeout=30, trust_env=False) as client:
                upstream = await client.post(f"{UPSTREAM}/tts", json=payload)
    except httpx.HTTPError as error:
        raise HTTPException(status_code=503, detail="local TTS upstream unavailable") from error
    if upstream.status_code != 200:
        raise HTTPException(status_code=502, detail="local TTS synthesis failed")
    return Response(
        content=upstream.content,
        media_type="audio/wav",
        headers={"X-Meguri-TTS-Model-Version": profile["id"]},
    )


def main() -> None:
    uvicorn.run(app, host="127.0.0.1", port=int(os.getenv("MEGURI_TTS_PORT", "9880")), workers=1)


if __name__ == "__main__":
    main()
