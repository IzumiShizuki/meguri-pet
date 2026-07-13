# Desktop AIRI spike

This is a small integration spike, not a fork of AIRI Stage Tamagotchi. It demonstrates the stable Meguri turn protocol, streaming reducer and renderer boundary in isolation before wiring them into AIRI's existing Vue/Pinia stores. The demo loads the canonical expression export and rejects a build ID mismatch or missing PNG before starting a turn.

Run `meguri-core` locally, then execute the demo with Node 24:

```powershell
$env:MEGURI_CORE_URL = 'http://127.0.0.1:8000'
D:\environment\nodejs\runtime\node-v24.17.0-win-x64\node.exe src\demo.ts
```

Future Live2D integration must implement `CharacterRenderer` by delegating to AIRI's `@proj-airi/stage-ui-live2d`. No Live2D assets or duplicate engine are included here.
