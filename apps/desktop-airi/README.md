# Desktop AIRI spike

This is a small integration spike, not a fork of AIRI Stage Tamagotchi. It demonstrates the stable Meguri turn protocol, streaming reducer and renderer boundary in isolation before wiring them into AIRI's existing Vue/Pinia stores. The demo loads the canonical expression export and rejects a build ID mismatch or missing PNG before starting a turn.

Run `meguri-core` locally, then execute the demo with Node 24:

```powershell
$env:MEGURI_CORE_URL = 'http://127.0.0.1:8000'
D:\environment\nodejs\runtime\node-v24.17.0-win-x64\node.exe src\demo.ts
```

Future Live2D integration must implement `CharacterRenderer` by delegating to AIRI's `@proj-airi/stage-ui-live2d`. No Live2D assets or duplicate engine are included here.

## Local visible desktop home

The repository does not vendor AIRI's Electron UI. For a visible local home
that uses the Java runtime and the canonical Meguri PNG assets, start Java on
`18080`, then run:

```powershell
D:\environment\nodejs\runtime\node-v24.17.0-win-x64\node.exe src\web-server.mjs
```

Open `http://127.0.0.1:5173`. The page uses the same `/v1/turns` + SSE
contract as the adapter; the DeepSeek credential remains in the Java process.

When a message explicitly asks for current information or web search, Java
uses the configured read-only search gateway and injects up to five bounded
title/link/summary records into the LangChain4j prompt. Ordinary character
chat stays local and does not trigger a search.

## Native floating overlay

The optional Electron shell follows the AIRI desktop boundary: frameless,
transparent, always-on-top, visible across workspaces, and hidden from the
taskbar. Start the web server first, then run `pnpm install` and `pnpm overlay`
from this directory. `Ctrl+Shift+M` toggles visibility. This shell hosts the
current Meguri web stage; AIRI's full Live2D/VRM renderer remains a later
asset/runtime integration.

## AIRI native stage adapter

The official AIRI Stage Tamagotchi checkout is kept separate from this
repository at `D:\program\airi-meguri` so AIRI's upstream workspace and license
boundaries stay intact. It is pinned to the checked-in AIRI reference revision
and contains only a thin Meguri provider package:

- `meguri-java` translates AIRI's OpenAI-compatible chat fetch into Java
  `POST /v1/turns` plus the Meguri session SSE stream.
- `meguri-local-tts` translates AIRI speech requests into the loopback
  `http://127.0.0.1:9880/tts/synthesize` service.
- Java's `semantic.completed` event remains authoritative for
  `expression_tag`, `expression_intensity`, `voice_style`, `outfit_code`,
  `expression_code`, `sprite_file`, and `motion_tag`. The adapter only emits an
  AIRI `<|ACT ...|>` marker so AIRI's native stage can render the cue.

Start the Java core and local TTS bridge first, then from the AIRI checkout use
the normal Stage Tamagotchi commands. The AIRI UI keeps any provider selection
already made by the user; when no provider is selected, the Meguri providers
are selected by default. AIRI cloud speech is not used, and the fine-tuned
GPT-SoVITS weights remain local.

The current Meguri repository still has no Live2D model asset. The Meguri AIRI
workspace therefore enables a PNG-first renderer whenever `meguri-java` is the
active provider. It loads the resolved `sprite_file` from the loopback asset
server, switches the actual standing illustration after each
`semantic.completed` event, and adds small CSS motions for the AIRI motion cue.
Live2D, VRM, Spine, and Godot scenes are hidden in this mode; AIRI's chat,
speech, window, tray, controls, and streaming pipelines remain native.

The visually reviewed runtime mapping is stored separately in
`configs/meguri_sprite_runtime_map.json`. It covers every combination of eight
outfits, twelve semantic expressions, and three intensities while leaving the
canonical 400-sprite dataset unchanged. The asset server on `127.0.0.1:5173`
must remain available to the AIRI renderer.
