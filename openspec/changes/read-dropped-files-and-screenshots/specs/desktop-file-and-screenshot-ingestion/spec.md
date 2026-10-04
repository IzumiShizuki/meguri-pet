## Purpose

让用户能以拖放文件或粘贴截图的明确动作，将受支持且有大小边界的真实内容交给 Meguri 查看，同时保持 Everything 搜索默认不读取内容的隐私边界。

## ADDED Requirements

### Requirement: Dropped files become approved readable attachments
The desktop SHALL accept regular local files dropped onto the chat area, validate each file at the trusted desktop boundary, and mark each supported file for the Core content reader before the user sends the turn.

#### Scenario: User drops a supported image or PDF
- **WHEN** the user drops a safe PNG, JPEG, GIF, WebP, or PDF file within the configured per-file limit
- **THEN** the composer SHALL display the file as an attachment marked for multimodal reading

#### Scenario: User drops a supported document
- **WHEN** the user drops a safe TXT, Markdown, CSV, JSON, YAML, or DOCX file within the configured document limit
- **THEN** the composer SHALL display the file as an attachment marked for document reading

#### Scenario: User drops an unsafe or unsupported item
- **WHEN** the dropped item is a directory, link, denied path, denied secret-like file, unsupported type, unavailable file, or exceeds its content limit
- **THEN** the desktop SHALL reject it before turn submission and show a specific recoverable error

### Requirement: Clipboard screenshots are viewable by Meguri
The desktop SHALL accept an image from the Windows clipboard only in response to an explicit paste action and SHALL submit it as bounded inline image content.

#### Scenario: User pastes a Snipaste screenshot
- **WHEN** Snipaste or another screenshot tool has placed an image on the Windows clipboard and the user pastes in the Meguri composer
- **THEN** the composer SHALL add a visible screenshot attachment that the model can receive as image content

#### Scenario: Clipboard has no image
- **WHEN** the user pastes ordinary text or the clipboard contains no image
- **THEN** normal text paste behavior SHALL remain unchanged and no attachment SHALL be created

#### Scenario: Clipboard image exceeds the limit
- **WHEN** the user pastes an image whose encoded content exceeds the configured attachment limit
- **THEN** the desktop SHALL not attach it and SHALL show an actionable size error

### Requirement: Attachment authorization is explicit and visible
The desktop SHALL distinguish readable attachments from Everything metadata references in the composer and SHALL only read content after the user submits the turn.

#### Scenario: Dragged or pasted attachment is staged
- **WHEN** a file or screenshot is accepted into the composer
- **THEN** the UI SHALL identify that its content will be read and SHALL allow the user to remove it before sending

#### Scenario: Everything result is selected
- **WHEN** the user selects a result from `@`/Everything search without a drag or paste action
- **THEN** the result SHALL remain metadata-only and SHALL not grant file-content access

#### Scenario: User retries a failed turn
- **WHEN** a turn with readable attachments fails before completion and the user retries
- **THEN** the same validated attachment payload SHALL remain available for that retry without broadening its access

### Requirement: Content delivery remains bounded and local-path safe
The desktop and Core SHALL preserve the existing local-only path validation, supported-content allowlists, per-attachment limits, aggregate limits, and local Core routing for readable attachments.

#### Scenario: Readable attachment is sent
- **WHEN** the user submits a turn containing a readable local file or inline screenshot
- **THEN** the gateway SHALL route the session to the local Core and the Core SHALL revalidate the payload before invoking a multimodal-capable model

#### Scenario: Renderer attempts to forge a path
- **WHEN** an untrusted, denied, or no-longer-valid path is supplied outside the explicit drag-validation flow
- **THEN** the trusted boundary or Core SHALL reject the request without reading the file
