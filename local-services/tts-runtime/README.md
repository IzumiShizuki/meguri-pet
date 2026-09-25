# Meguri local TTS runtime

This loopback-only bridge reuses the existing GPT-SoVITS v2Pro fine-tuned
weights and exposes `configs/local_tts_contract.json` for AIRI. It maps
`voice_style + expression_intensity` to bounded speed/temperature/top-p values;
it does not change the Java LLM schema or the Notion emotion contract.

Start it with the repository helper after the Java service is running:

```powershell
powershell -ExecutionPolicy Bypass -File ops/scripts/start_local_tts.ps1
```

The bridge listens only on `127.0.0.1:9880`. It starts the upstream
`GPT-SoVITS/api_v2.py` on an internal loopback port and stops it with the
bridge. AstrBot, website and cloud services must not call this endpoint.
