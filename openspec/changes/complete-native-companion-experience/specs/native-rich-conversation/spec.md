## Purpose

Make assistant replies readable as formatted Markdown and mathematical expressions on both native conversation surfaces, while retaining safe and accessible copying.

## ADDED Requirements

### Requirement: Safe rich replies
Assistant replies SHALL support Markdown headings, lists, links, tables and code plus inline and block LaTeX. Rendering MUST disable raw HTML and trusted executable math commands, block unsafe URL schemes and remote image loading, and preserve plain-text fallback for malformed content. Non-assistant messages MUST remain plain text.

#### Scenario: Formatted reply
- **WHEN** an assistant reply contains Markdown and valid math
- **THEN** the chat dock and Galgame view display matching formatted content and formulas.

#### Scenario: Unsafe or malformed reply
- **WHEN** the reply contains script-like HTML, dangerous links, remote images or invalid math
- **THEN** it cannot execute code or initiate remote image requests and remains readable.

### Requirement: Copy and keyboard access
Conversation text SHALL remain selectable and provide a keyboard-accessible copy action that copies the original message and reports clipboard errors.

#### Scenario: Copy a reply
- **WHEN** the user activates copy
- **THEN** the original message, including its Markdown and math source, is copied or an actionable error is displayed.
