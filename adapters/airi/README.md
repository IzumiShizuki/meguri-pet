# Meguri AIRI adapter spike

This package is an independent adapter for AIRI Stage Tamagotchi. It does not fork AIRI, import AIRI memory internals, or implement a competing Live2D engine.

`MeguriApiAdapter` creates and cancels turns through HTTP, consumes the stable SSE envelope, reconnects from the last accepted sequence, ignores duplicate replay, and surfaces sequence gaps. AIRI renderer integration should bind reducer state to the existing `@proj-airi/stage-ui` streaming UI and delegate future Live2D cues to `@proj-airi/stage-ui-live2d`.
