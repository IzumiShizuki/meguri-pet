## Context

ChatDock currently uses one expanded flag for both history and functional panels. Speech playback already serializes local synthesis and audio, supports cancellation, and drives mouth animation. Live voice calls depend on its completion signal. See proposal.md for motivation.

## Goals / Non-Goals

**Goals:** Separate history visibility from window size and provide explicit reply playback with shared state across compact, history, and Galgame views.

**Non-Goals:** New speech providers, server API changes, cached audio storage, or changing live-call recognition.

## Decisions

1. Use saved chat expansion exclusively to show history. Compute large-bubble mode separately from Todo, reminders, and attachments. Errors remain a compact inline notice. Tool bubbles contain tool content, without an empty history area. Keep the draft composer mounted across switches.
2. Keep a single latest-reply preview above the input in compact mode. Give it a separate expand-chat button and replay control; clicking replay cannot expand history. Use native buttons, avoiding nested interactive elements.
3. Add optional speech metadata to companion messages when a TTS event is accepted. Explicit replay uses the captured speech text/style/intensity; older replies fall back to their text and a neutral voice. Existing persisted messages remain compatible.
4. Track the selected replay message in the conversation store and use a shared replay button for compact and historical messages. Playback cancellation clears its busy state. A request token prevents completion of superseded audio from clearing a newer replay's state. Automatic TTS, new turns, conversation resets, and voice calls keep the existing single-audio behavior.
5. Manual replay explicitly enables speech for that request while retaining the configured volume/speed. It does not change automatic speech preferences, resubmit a conversation turn, or consume a protocol event again. The control offers stop/loading state and local failure feedback. Disable it during a live voice call or an unfinished response.

## Risks / Trade-offs

- Local TTS may be unavailable → show an actionable error at the selected reply and preserve the text and draft.
- Old histories lack speech metadata → replay their original text without changing the stored reply.
- Tool content may exceed viewport height → use the existing scrollable body, an inline attachment panel, and verify narrow viewport behavior in Electron.
- Overlapping replay and automatic speech → cancel the previous audio and guard state with a request token; verify completion/cancellation with mocked local audio.
