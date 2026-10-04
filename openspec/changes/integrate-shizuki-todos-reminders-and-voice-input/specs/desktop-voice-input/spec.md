## Purpose

Gives the owner a local microphone path for composing Chinese messages in the native Meguri desktop, with a visible transcript that can be corrected before sending or turning it into a task.

## ADDED Requirements

### Requirement: Explicit voice capture
Meguri SHALL start listening only after the user activates the microphone control and SHALL let the user cancel capture. It SHALL indicate listening, recognition, success, and failure states.

#### Scenario: Spoken Chinese utterance
- **WHEN** the user activates voice input and speaks an utterance recognized by the installed Chinese speech engine
- **THEN** Meguri places the transcript into the editable chat draft without sending it

#### Scenario: Cancel capture
- **WHEN** the user cancels an active capture
- **THEN** Meguri stops listening and leaves the existing draft unchanged

#### Scenario: Engine unavailable
- **WHEN** the microphone or installed recognizer cannot be used
- **THEN** Meguri shows an actionable error and preserves the current draft

### Requirement: Voice privacy and review
Meguri MUST NOT persist or upload raw microphone audio for this feature and MUST NOT automatically create a todo or reminder from recognized text. The user SHALL be able to edit the transcript before any action.

#### Scenario: Transcript needs correction
- **WHEN** recognition returns text with an incorrect time or title
- **THEN** the user can correct the draft before sending or creating a todo
