## Context

See `proposal.md` for motivation and `specs/desktop-live-voice-conversation/spec.md` for observable behavior. The desktop already exposes a trusted stage-only `voice.start()` / `voice.cancel()` bridge around a local Windows `System.Speech` recognition process. `conversation.send()` already enforces consent, adds the transcript to history, handles the existing turn/SSE flow, and triggers configured local TTS. The current TTS returns a complete audio blob and exposes cancellation, but there is no completion signal for coordinating the next microphone cycle.

## Goals / Non-Goals

**Goals:**
- Keep one explicit call session alive across multiple speech turns.
- Reuse the recognizer, conversation pipeline, and TTS preferences already in Meguri.
- Keep microphone capture stopped while Meguri is thinking or speaking.
- Make session orchestration testable without opening a real microphone or contacting the chat backend.

**Non-Goals:**
- Full-duplex listening, talking over Meguri, wake-word activation, WebRTC/telephony, live partial captions, or replacing the speech engine in this change.
- Adding a speech framework or new model/runtime dependency.

## Decisions

### Reuse the one-utterance Windows recognizer in a renderer-owned session loop

The renderer controller repeatedly calls the existing `voice.start()` adapter. A non-empty final transcript is sent once with `conversation.send()`. A no-speech result starts another local recognition cycle; a fatal recognition or send error ends the call. Hanging up invalidates the session generation and cancels the current recognition process. This keeps lifecycle logic behind one small injected interface and allows deterministic tests with fake recognizers.

The loop is intentionally half-duplex: it does not start recognition again until the conversation turn finishes and any enabled TTS playback is done. That avoids hearing Meguri through the microphone and does not claim barge-in support.

### Add a completion wait to the existing TTS module

`playSpeech()` will track its active synthesis/playback task, and an exported wait function will resolve after that task has either finished, failed, or been cancelled. The voice controller waits on this function after `conversation.send()` returns. Existing callers continue to start speech without blocking their message stream.

### Keep existing IPC and consent seams

No new microphone IPC is needed: the session calls the existing stage-only start/cancel methods. Final text goes through the existing `conversation.send()` path, retaining privacy consent, persistence settings, conversation history, and error handling. No audio leaves the local recognizer or is written to disk. Existing TTS preference flags are respected; a disabled TTS path is already idle and lets the session continue.

### Render the session state in the existing composer control

The composer microphone becomes a toggle with idle, listening, thinking, speaking, and error status. The state callback owns renderer state; existing one-shot IPC change events are not used to inject call transcripts into the manual draft. The component stops the session when it unmounts, and manual text submission is disabled during the call.

## Risks / Trade-offs

- **[Process startup between utterances adds a small gap]** → Reuse the already-tested local helper first; measure with real user speech before deciding whether a persistent streaming recognizer is needed.
- **[A 20-second recognition timeout can delay re-arming during long silence]** → Treat no-speech as recoverable and automatically start another recognition cycle while the call remains active.
- **[Meguri cannot be interrupted by speech while it responds]** → Keep a visible hang-up control that cancels both the conversation turn and TTS. Pipecat documents interruption/turn handling patterns for a later full-duplex iteration; AIRI provides a closer Electron/Vue lifecycle reference.
- **[The call may continue without audio output if TTS is disabled or unavailable]** → Follow existing speech settings and keep the text response visible; preserve any TTS error hint.

## Migration Plan

No persisted format or server protocol changes. Replacing the composer control is reversible; the one-shot local recognizer remains available to the controller as its recognition adapter. If the call is stopped, the next manual message uses the same existing chat flow.
