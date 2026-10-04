## 1. Application foundation

- [x] 1.1 Scaffold `apps/desktop-meguri` with Electron main/preload, Vite, Vue, Pinia, TypeScript, and focused build/test scripts.
- [x] 1.2 Define the narrow preload contract and shared types for readiness, window actions, safe artifact operations, and presentation persistence.
- [x] 1.3 Extend the existing desktop gateway identity checks to accept the native Meguri client while preserving AIRI behavior and add regression tests.

## 2. Native shell and startup

- [x] 2.1 Implement persisted, display-safe pet-window bounds and the transparent always-on-top window lifecycle with unit tests for geometry policy.
- [x] 2.2 Implement the startup readiness coordinator for gateway/Core, local TTS, canonical sprite, and renderer readiness with retryable failure states.
- [x] 2.3 Build the dedicated startup renderer and transition from the startup window to the stage only after minimum readiness is satisfied.

## 3. AIRI-familiar Meguri stage

- [x] 3.1 Create Meguri visual tokens and the transparent stage composition with character halo, floating status/control island, artifact layer, and compact/expanded chat dock.
- [x] 3.2 Implement the stage presentation store with persisted dock/outfit/motion preferences and reduced-motion behavior.
- [x] 3.3 Implement canonical sprite URL resolution, neutral fallback, expression/intensity/outfit state, speaking treatment, and motion classes with tests.

## 4. Conversation and artifacts

- [x] 4.1 Implement a canonical conversation client/store with persisted identity, session checkpoint, streaming text, terminal states, retry, cancellation, and new-conversation isolation.
- [x] 4.2 Build the message history, composer, streaming response, error/retry, compact/expanded dock, and keyboard interactions.
- [x] 4.3 Implement artifact-feed polling, de-duplication, history links, at-most-three dismissible bubbles, default-app opening, and icon fallback with tests.

## 5. Integration and migration entrypoint

- [x] 5.1 Add workspace commands and a native Meguri launcher that reuses the existing Core, gateway, and TTS processes without changing the AIRI fallback launcher.
- [x] 5.2 Document native frontend architecture, development commands, current AIRI parity, known gaps, and the ordered parity roadmap.

## 6. Verification

- [x] 6.1 Run OpenSpec strict validation, frontend unit tests, gateway security tests, TypeScript checks, and production builds.
- [x] 6.2 Use Playwright to verify startup, compact/expanded chat, sending and retry states, artifact interactions, keyboard focus, reduced motion, and responsive layouts.
- [x] 6.3 Capture and inspect deterministic screenshots of the startup and stage views, then correct material visual or layout defects.
- [x] 6.4 Add a real sandboxed Electron preload regression test and fix the missing desktop bridge that left startup readiness at 0%.
- [x] 6.5 Verify the rendered stage is composited into the real Windows desktop window and fix the fully transparent host-window regression.
