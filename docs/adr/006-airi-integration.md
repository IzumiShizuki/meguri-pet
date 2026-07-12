# ADR 006: AIRI integration

Status: accepted for spike.

Implement a `MeguriApiAdapter` and `CharacterRenderer` boundary. Version 1 uses PNG; a future adapter delegates Live2D to AIRI. The first spike covers text deltas, expression cues, cancellation, reconnect, and a local TTS mock only.

