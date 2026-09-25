$ErrorActionPreference = 'Stop'
$root = 'D:\program\meguri-pet'
$python = 'D:\environment\miniconda3\envs\GPTSoVits\python.exe'
$env:MEGURI_TTS_PYTHON = $python
$env:MEGURI_TTS_MODEL_VERSION = 'meguri_v2_02c3db0c507d7c2d-baseline_001-e4'
$env:MEGURI_TTS_CONFIG = Join-Path $root 'configs\tts_infer_v2pro_full_ja_extended_denoised.yaml'
$env:MEGURI_TTS_REF_AUDIO = Join-Path $root 'data\meguri\assets\voice_safe\MGR000238.ogg'
$env:MEGURI_TTS_LOG_DIR = 'D:\environment\logs\meguri'
New-Item -ItemType Directory -Force -Path $env:MEGURI_TTS_LOG_DIR | Out-Null
$log = Join-Path $env:MEGURI_TTS_LOG_DIR 'meguri-tts-bridge.log'
$err = Join-Path $env:MEGURI_TTS_LOG_DIR 'meguri-tts-bridge.err.log'
Remove-Item $log, $err -Force -ErrorAction SilentlyContinue
Start-Process -WindowStyle Hidden -PassThru -WorkingDirectory $root `
  -FilePath $python `
  -ArgumentList '-m', 'uvicorn', 'local-services.tts-runtime.server:app', '--host', '127.0.0.1', '--port', '9880', '--workers', '1' `
  -RedirectStandardOutput $log -RedirectStandardError $err | Select-Object -ExpandProperty Id
