## Why

Meguri’s microphone button currently recognizes one short utterance and places it in the draft. Users must send each phrase manually and start recognition again, so it cannot support an ongoing spoken conversation.

## What Changes

- Turn the composer microphone into an explicit long-call control with visible listening, thinking, and speaking states plus a clear hang-up action.
- In an active call, automatically send each completed local recognition result through the existing conversation flow, speak replies through the existing local TTS setting, and listen again after the reply finishes.
- Pause microphone recognition while Meguri generates or speaks a reply to prevent feedback; hanging up cancels recognition, the active conversation turn, and speech playback.
- Keep microphone audio local and apply the existing chat-consent rules to recognized text. Do not persist or upload audio.
- Research existing open-source voice-session projects and document which parts are useful references for Meguri.

## Capabilities

### New Capabilities
- `desktop-live-voice-conversation`: An explicit, turn-based spoken conversation session in the desktop chat composer.

### Modified Capabilities

## Impact

- `apps/desktop-meguri/src/renderer/components/ChatDock.vue`, a new voice-session controller, and speech playback coordination.
- The existing trusted stage voice bridge and conversation store; no IPC or server protocol change.
- Desktop voice documentation and a research note comparing AIRI, Pipecat, LiveKit Agents, and sherpa-onnx.
- No new runtime dependency or remote audio service.
