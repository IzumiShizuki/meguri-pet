## Purpose

Provide lightweight desktop reply bubbles and readable tool panels while keeping the companion's size stable and fitting the native window to the active interaction.

## ADDED Requirements

### Requirement: Compact reply bubble
The desktop SHALL show a bounded preview of the latest companion reply and a compact message composer when conversation history is collapsed. Selecting the reply SHALL open the full conversation. Layout switches SHALL preserve drafts, attachments, and conversation state.

#### Scenario: Collapse and reopen history
- **WHEN** the user collapses a conversation containing a long reply and an unsent draft
- **THEN** only the bounded latest-reply bubble and compact composer remain, and reopening shows the complete reply and unchanged draft

#### Scenario: Streaming reply in compact mode
- **WHEN** the companion receives streamed reply text while collapsed
- **THEN** the small reply bubble updates without expanding the character or window

### Requirement: Large functional bubble
The desktop SHALL provide a larger bubble for history, Todo, reminder review, attachment selection, skills, and work. Temporary tools SHALL return to the prior compact presentation after closing unless the user explicitly expanded history or another tool is active.

#### Scenario: Todo from compact presentation
- **WHEN** the user opens Todo from the compact bubble
- **THEN** the Todo contents and controls appear in a large readable bubble, and closing Todo returns to compact mode without discarding the chat draft

#### Scenario: Reminder and attachment review
- **WHEN** reminder confirmation or attachment selection becomes active from compact mode
- **THEN** the window provides a large review bubble and keeps the input controls accessible

#### Scenario: Skills and work
- **WHEN** the user opens skills or work from the compact pet
- **THEN** the tool appears in the large bubble area, and closing the last tool restores compact mode

### Requirement: Stable character and fitted window
The desktop SHALL preserve the same PNG and Live2D portrait dimensions across expansion, collapse, and native resizing. It SHALL fit compact bounds after collapse, migrate legacy expanded layout sizes, and keep the portrait's screen position where the current display permits. Panels SHALL remain within the viewport on narrow displays; Galgame SHALL restore the prior ambient layout when exited.

#### Scenario: Repeated expand and collapse
- **WHEN** the user repeatedly expands, resizes, and collapses chat
- **THEN** the portrait does not shrink and the compact window contains no residual expanded-size space

#### Scenario: Legacy size and constrained display
- **WHEN** a saved tall expanded window is restored or a panel opens on a narrow display
- **THEN** the new bubble layout is fitted to the current display without stretching the portrait or losing panel controls

#### Scenario: Return from Galgame
- **WHEN** the user leaves Galgame
- **THEN** the corresponding compact or expanded ambient window is restored with its portrait scale unchanged
