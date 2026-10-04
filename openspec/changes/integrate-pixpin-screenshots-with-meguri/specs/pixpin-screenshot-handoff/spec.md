## Purpose

Let a desktop user deliberately select a screen region with PixPin and give that one image to Meguri, either to compose a question or to send a preselected screenshot request, while keeping capture bounded and visible.

## ADDED Requirements

### Requirement: Expose two explicit screenshot handoff actions
The desktop SHALL offer separate user actions for capturing a PixPin image into the current Meguri draft and for capturing and submitting an image with a visible default question. Neither action SHALL start without a user gesture.

#### Scenario: Capture to ask a question
- **WHEN** the user chooses the ask-about-screenshot action and completes a PixPin selection
- **THEN** the selected image is staged as a removable readable attachment in the current composer
- **AND** the user's existing draft text and attachments remain available
- **AND** the user can type or edit a question before sending

#### Scenario: Capture and send directly
- **WHEN** the user chooses the direct-send action and completes a valid PixPin selection
- **THEN** the desktop submits exactly one turn containing that selected image and an explicit default question
- **AND** the submitted image and question are visible in conversation history

#### Scenario: Another turn is being sent
- **WHEN** the user requests direct-send while a turn or capture is already active
- **THEN** the desktop SHALL reject or defer the new action with a visible explanation rather than silently sending a duplicate turn

### Requirement: Receive only the result of the active capture
Each PixPin handoff SHALL have a bounded one-shot lifecycle. The desktop MUST NOT treat a pre-existing clipboard image, a cancelled selection, or a later unrelated clipboard update as the result of that capture.

#### Scenario: Fresh completed selection
- **WHEN** PixPin places a newly completed screenshot on the clipboard during the active capture window
- **THEN** the desktop accepts that screenshot once, including when its pixels match an older screenshot
- **AND** the capture session closes after receipt

#### Scenario: Selection is cancelled or times out
- **WHEN** the user cancels PixPin selection or no new screenshot arrives before the capture deadline
- **THEN** no image is attached or submitted
- **AND** the desktop returns to a usable state with a clear cancellation or timeout status

#### Scenario: Unrelated clipboard update
- **WHEN** the clipboard changes to text, an unsupported format, or an image that cannot be attributed to the active PixPin selection
- **THEN** the desktop SHALL NOT add or submit it through PixPin handoff

### Requirement: Preserve existing attachment and vision boundaries
The handoff SHALL reuse the desktop's readable-image limits and the local multimodal turn path. It MUST NOT bypass attachment validation, local routing, vision-model requirements, or failed-send recovery.

#### Scenario: Screenshot is too large
- **WHEN** the captured image exceeds the configured image or aggregate attachment limit
- **THEN** it is not submitted and the desktop shows an actionable size error

#### Scenario: No usable vision route
- **WHEN** the user attempts a direct send but no vision-capable model is configured
- **THEN** the desktop SHALL NOT claim the screenshot was understood
- **AND** it SHALL retain the image for a later retry or provide an actionable configuration path

#### Scenario: Submitted turn fails
- **WHEN** a screenshot turn fails after submission
- **THEN** the user can retry with the same validated image and question without taking another screenshot

### Requirement: Handle unavailable PixPin and preserve manual paste
The desktop SHALL identify an unavailable or unusable PixPin integration before launching capture and SHALL keep the existing user-initiated clipboard paste path available.

#### Scenario: PixPin is not installed or running
- **WHEN** the user invokes a PixPin action without a usable PixPin instance
- **THEN** the desktop shows setup or startup guidance and does not create an empty capture session

#### Scenario: User uses manual capture and paste
- **WHEN** the user copies a screenshot in PixPin and explicitly pastes it into Meguri
- **THEN** the existing clipboard-image attachment behavior remains available without a PixPin-specific configuration

#### Scenario: User starts capture in PixPin and imports it explicitly
- **WHEN** the user completes a PixPin screenshot and invokes Meguri's clipboard-import shortcut
- **THEN** Meguri stages the current standard-image clipboard content through the existing image validator
- **AND** it does not monitor the clipboard before that explicit action

#### Scenario: PixPin copies an image as a file
- **WHEN** the current PixPin setting exposes only a file-backed clipboard entry
- **THEN** Meguri explains how to switch PixPin to standard image clipboard data
- **AND** it does not read a PixPin cache path that has not been verified for this capture

#### Scenario: Clipboard-import shortcut is unavailable
- **WHEN** another application owns Meguri's configured clipboard-import shortcut
- **THEN** Meguri shows that the shortcut conflicted and continues to support Ctrl+V paste

#### Scenario: Visibility shortcut restores Meguri above a screenshot window
- **WHEN** Meguri is still visible but is unfocused or covered by a PixPin screenshot window, and the user presses Meguri's visibility shortcut
- **THEN** Meguri re-applies its saved always-on-top preference, shows and focuses the stage window instead of hiding it
- **AND** pressing the shortcut again while Meguri is focused hides it

### Requirement: Keep capture permission scoped to the user's selection
The handoff SHALL read screen content only as a result of the user's current screenshot action. It SHALL NOT enable continuous clipboard monitoring, automatic screen observation, or persistent screen-read permission.

#### Scenario: No capture is active
- **WHEN** the user has not invoked a screenshot action
- **THEN** the PixPin handoff reads no clipboard image and creates no screen observation context

#### Scenario: User points to a region
- **WHEN** the user completes one PixPin region selection
- **THEN** authorization applies only to that selected image and the chosen draft-or-send action
- **AND** it does not authorize future captures or automatic Glance observation
