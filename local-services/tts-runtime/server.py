"""Loopback bridge from AIRI's speech provider to the local GPT-SoVITS v2Pro API.

The bridge owns the local model process and exposes only the repository's
``configs/local_tts_contract.json``. It never binds beyond 127.0.0.1.
"""

from __future__ import annotations

import asyncio
import os
import signal
import subprocess
from contextlib import asynccontextmanager
from pathlib import Path
from typing import AsyncIterator

import httpx
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

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


class SynthesisRequest(BaseModel):
    text: str = Field(min_length=1, max_length=1000)
    voice_style: str = "neutral"
    expression_intensity: str = "medium"
    request_id: str = Field(min_length=1, max_length=160)


class RuntimeState:
    process: subprocess.Popen[bytes] | None = None
    ready: bool = False


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
    log_dir = Path(os.getenv("MEGURI_TTS_LOG_DIR", r"D:\environment\logs\meguri"))
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
    state.process = start_upstream()
    try:
        await wait_upstream()
        yield
    finally:
        await stop_upstream()


app = FastAPI(title="Meguri Local TTS", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, object]:
    return {"status": "ok" if state.ready else "starting", "model_version": MODEL_VERSION, "ready": state.ready}


@app.post("/tts/synthesize")
async def synthesize(request: SynthesisRequest) -> Response:
    if not state.ready:
        raise HTTPException(status_code=503, detail="local TTS is not ready")
    speed, temperature, top_p = style_parameters(request.voice_style, request.expression_intensity)
    payload = {
        "text": request.text,
        "text_lang": language_for(request.text),
        "ref_audio_path": str(REF_AUDIO),
        "prompt_lang": "ja",
        "prompt_text": REF_TEXT,
        "text_split_method": "cut5",
        "media_type": "wav",
        "streaming_mode": False,
        "speed_factor": speed,
        "temperature": temperature,
        "top_p": top_p,
        "batch_size": 1,
    }
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            upstream = await client.post(f"{UPSTREAM}/tts", json=payload)
    except httpx.HTTPError as error:
        raise HTTPException(status_code=503, detail="local TTS upstream unavailable") from error
    if upstream.status_code != 200:
        raise HTTPException(status_code=502, detail="local TTS synthesis failed")
    return Response(
        content=upstream.content,
        media_type="audio/wav",
        headers={"X-Meguri-TTS-Model-Version": MODEL_VERSION},
    )


def main() -> None:
    uvicorn.run(app, host="127.0.0.1", port=int(os.getenv("MEGURI_TTS_PORT", "9880")), workers=1)


if __name__ == "__main__":
    main()
