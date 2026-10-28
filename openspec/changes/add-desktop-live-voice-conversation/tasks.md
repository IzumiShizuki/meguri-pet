## 1. Voice session orchestration

- [x] 1.1 Add an injected, generation-safe long-call controller that repeatedly recognizes, auto-sends final transcripts, waits after no-speech results, and stops on fatal errors.
- [x] 1.2 Add controller tests for repeated turns, recoverable silence, fatal recognition failure, duplicate start prevention, and hang-up during recognition or reply.

## 2. Conversation and playback integration

- [x] 2.1 Add a waitable completion signal for the active local TTS task, including cancellation and synthesis/playback errors.
- [x] 2.2 Wire the controller to the existing trusted voice bridge and `conversation.send()` / `conversation.cancel()` actions, preserving existing chat consent and TTS settings.
- [x] 2.3 Verify that call transcripts are sent once, appear in history, and do not enter the manual draft.

## 3. Desktop UI and guidance

- [x] 3.1 Replace the composer microphone action with a long-call toggle and visible listening, thinking, speaking, error, and hang-up states.
- [x] 3.2 Stop an active call on component unmount and prevent overlapping manual sends while the call is active.
- [x] 3.3 Update desktop documentation to explain turn-based long calls, local audio handling, settings, and current half-duplex limitations.
- [x] 3.4 Save the open-source project comparison and primary-source references in the repository research note.

## 4. Verification

- [x] 4.1 Run focused unit tests and desktop type checking for the changed renderer/main modules.
- [x] 4.2 Validate the OpenSpec change and inspect the final diff to confirm unrelated working-tree changes remain untouched.
