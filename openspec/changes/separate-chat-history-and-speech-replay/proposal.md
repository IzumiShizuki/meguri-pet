## Why

Temporary reminder and attachment panels currently reveal the entire chat history even when the user has not expanded chat. Replies have automatic speech support but no explicit replay control, so users cannot listen to one reply again.

## What Changes

- Keep the default presentation to one latest-reply bubble and an input below it. Show conversation history only after the user selects the expand-chat control.
- Keep Todo, reminder review, and attachments in their own large functional bubble without exposing history.
- Add a speech replay/stop control to the latest reply and each completed companion message, including Galgame history. Preserve the original speech text and style when available and show playback failures at the selected reply.
- Preserve drafts, stable portrait dimensions, automatic speech preferences, and ongoing voice-call behavior.

## Capabilities

### New Capabilities

- `chat-history-and-replay`: Explicit history visibility, independent tool bubbles, and user-initiated playback of completed companion replies.

### Modified Capabilities

None. This refines the unarchived `fix-compact-chat-bubbles` change.

## Impact

Desktop ChatDock, MessageContent, Galgame history, conversation persistence, and speech playback controls. Uses the existing local speech service; no new dependencies or remote API changes.
