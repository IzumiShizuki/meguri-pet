# AIRI upstream inventory

- Reference checkout: `D:\program\references\airi-upstream`
- Repository: `https://github.com/moeru-ai/airi.git`
- Commit: `563a738c88cc42967fd0721a6ca5ad0c9aa403a6`
- Upstream version: `0.11.0`
- License: MIT
- Upstream package manager: `pnpm@10.33.0`
- Local Node.js: `v24.17.0`
- Local pnpm: `11.8.0`
- Root desktop command: `pnpm dev:tamagotchi`
- Stage package command: `pnpm -rF @proj-airi/stage-tamagotchi run dev`

The checkout is sparse and used as a read-only reference. Relevant integration observations:

- `stage-tamagotchi` is an Electron/Vue application and imports its chat session/runtime UI from `@proj-airi/stage-ui`.
- AIRI server SDK already models announce/ready, heartbeat, abort signals, event listeners and reconnect state. Meguri borrows those lifecycle principles but keeps its own stable turn envelope.
- AIRI exposes `@proj-airi/stage-ui-live2d`; Meguri must delegate a future renderer to that package instead of copying or rebuilding the Live2D engine.
- The first Meguri spike remains independent of AIRI internal storage and provider schemas: HTTP creates/cancels turns, SSE carries ordered events, and `CharacterRenderer` receives semantic cues.
