# Meguri AIRI adapter spike

This package is an independent adapter for AIRI Stage Tamagotchi. It does not fork AIRI, import AIRI memory internals, or implement a competing Live2D engine.

`MeguriApiAdapter` creates and cancels turns through HTTP, consumes the stable SSE envelope, reconnects from the last accepted sequence, ignores duplicate replay, and surfaces sequence gaps. AIRI renderer integration should bind reducer state to the existing `@proj-airi/stage-ui` streaming UI and delegate future Live2D cues to `@proj-airi/stage-ui-live2d`.

Before creating a Turn, the adapter sends canonical `/v1/hello` with distinct
Meguri user, AIRI platform actor, client instance, and session identities. The
selected protocol minor, server capability revision, and reducer checkpoint can
be persisted through the optional adapter storage. The desktop runtime writes
the accepted checkpoint before renderer, TTS, animation, notification, or other
ONCE side effects. Checkpoints and reducers are isolated by Session, so one AIRI
runtime can switch conversations without carrying sequence or ONCE state across
Session boundaries.
